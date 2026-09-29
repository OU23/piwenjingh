<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageContainer from '@/components/PageContainer.vue';
import ToolFilterBar from '@/components/ToolFilterBar.vue';
import ToolGrantDialog from '@/components/ToolGrantDialog.vue';
import ToolTable from '@/components/ToolTable.vue';
import { listTags, type TagVO } from '@/api/tag';
import { batchDeleteTools, deleteTool, getToolDetail, listTools, saveToolGrants, updateTool, type ToolVO } from '@/api/tool';
import { ws } from '@/api/ws';
import type { ID } from '@/api/types';
import { PAGE_SIZE_DEFAULT, SCOPE, SCOPE_LABEL, type ScopeValue } from '@/constants/enums';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import {
  buildToolListQuery,
  createDefaultFilters,
  hasActiveFilters,
  normalizePageSize,
  type ToolFilters,
} from '@/utils/toolQuery';

/**
 * 工具列表（首页）：检索 + 筛选 + 分页，四态齐全，两种空态区分
 * 见 docs/plan/P2-前端核心页面.md T3、docs/前端蓝图.html 第 3 页
 */
const router = useRouter();
const userStore = useUserStore();

const filters = ref<ToolFilters>(createDefaultFilters());
const pageNum = ref(1);
const pageSize = ref(PAGE_SIZE_DEFAULT);
const list = ref<ToolVO[]>([]);
const total = ref(0);
const tags = ref<TagVO[]>([]);
const tagsError = ref('');
const loading = ref(false);
const error = ref<string | null>(null);
/** 当前多选行（用于批量删除） */
const selectedRows = ref<ToolVO[]>([]);

/** 工具侧「授权」弹窗（契约 §9.8，v1.7 新增） */
const grantVisible = ref(false);
const grantTool = ref<ToolVO | null>(null);
const grantSubmitting = ref(false);
const grantError = ref('');

const canDelete = computed(() => userStore.has(PERMISSION.TOOL_DELETE));
const filtering = computed(() => hasActiveFilters(filters.value));
const isEmpty = computed(() => !loading.value && !error.value && list.value.length === 0);

/** 两种空态：尚无任何工具（引导上传）· 筛选无结果（引导重置） */
const emptyText = computed(() => (filtering.value ? '没有匹配的工具' : '还没有任何工具'));
const emptyHint = computed(() =>
  filtering.value ? '试试更换关键词，或清空筛选条件后重试' : '上传第一个工具后，团队成员就能在这里检索与下载',
);
const emptyActionText = computed(() => {
  if (filtering.value) {
    return '重置筛选';
  }
  return userStore.has(PERMISSION.TOOL_UPLOAD) ? '上传工具' : '';
});

async function fetchList(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const result = await listTools(buildToolListQuery(filters.value, pageNum.value, pageSize.value));
    list.value = result.list;
    total.value = result.total;

    // 筛选或删除后当前页可能已越界：回第一页重取，避免停在空白页
    if (result.list.length === 0 && pageNum.value > 1) {
      pageNum.value = 1;
      await fetchList();
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '列表加载失败，请稍后重试';
    list.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
    // 表格因 loading 切换会被 PageContainer 卸载重建，重建后视觉勾选必然丢失；
    // 同步清空父级多选态，避免「批量删除」按钮因僵尸 selectedRows 不消失（Bug 1 修复）
    selectedRows.value = [];
  }
}

async function fetchTags(): Promise<void> {
  tagsError.value = '';
  try {
    tags.value = await listTags();
  } catch (cause) {
    // 标签字典加载失败不阻断列表检索，但必须可见（验收 A3：无静默失败）
    tags.value = [];
    tagsError.value = cause instanceof Error ? cause.message : '标签字典加载失败';
  }
}

function handleSearch(next: ToolFilters): void {
  filters.value = next;
  pageNum.value = 1;
  void fetchList();
}

function handleReset(): void {
  filters.value = createDefaultFilters();
  pageNum.value = 1;
  void fetchList();
}

function handleSelectionChange(rows: ToolVO[]): void {
  selectedRows.value = rows;
}

/** 打开「授权」弹窗：先定工具，再在弹窗内多选用户（契约 §9.8，v1.7） */
function openGrant(row: ToolVO): void {
  grantTool.value = row;
  grantError.value = '';
  grantVisible.value = true;
}

/** 保存该工具的授权用户（全量覆盖，单接口）；失败**就地提示、弹窗保留**便于重试 */
async function handleSaveGrants(payload: { userIds: ID[] }): Promise<void> {
  if (!grantTool.value || grantSubmitting.value) {
    return;
  }

  const { id, name } = grantTool.value;
  grantSubmitting.value = true;
  grantError.value = '';

  try {
    await saveToolGrants(id, { userIds: payload.userIds });
    ElMessage.success(`「${name}」的授权已保存`);
    grantVisible.value = false;
  } catch (cause) {
    grantError.value = cause instanceof Error ? cause.message : '授权保存失败，请稍后重试';
  } finally {
    grantSubmitting.value = false;
  }
}

/** 行内删除：软删除（进回收站，可由管理员恢复） */
async function handleDelete(row: ToolVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除后「${row.name}」及其全部版本将进入"已删除工具"，可由管理员恢复。确定删除吗？`,
      '删除工具',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  try {
    await deleteTool(row.id);
    ElMessage.success(`「${row.name}」已删除，可在"已删除工具"中恢复`);
    await fetchList();
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '删除失败，请稍后重试');
  }
}

/** 批量删除：软删除（进回收站，可由管理员恢复） */
async function handleBatchDelete(): Promise<void> {
  const rows = selectedRows.value;
  if (rows.length === 0) {
    return;
  }

  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${rows.length} 个工具吗？将进入"已删除工具"，可由管理员恢复。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  try {
    await batchDeleteTools(rows.map((row) => row.id));
    ElMessage.success(`已删除 ${rows.length} 个工具`);
    selectedRows.value = [];
    await fetchList();
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '批量删除失败，请稍后重试');
  }
}

/**
 * 变更工具可见范围（需求 11 / P14）：DEPT ↔ PUBLIC。
 * 两个方向都需二次确认（收窄影响他人可见性；放开需明示全员可见）。
 * ⚠️ `PUT /api/tools/{id}` 为**全量覆盖式**提交（ToolUpdateDTO 必填 name / version / intro / fileExt / scope），
 *    而列表 ToolVO **不含** readme / platform / dependency → 必须先取详情再提交，否则会丢字段（P14 计划 §3）。
 */
async function handleScopeChange(row: ToolVO, scope: ScopeValue): Promise<void> {
  if (scope === row.scope) {
    return;
  }

  const narrowing = row.scope === SCOPE.PUBLIC && scope === SCOPE.DEPT;
  const confirmText = narrowing
    ? `将「${row.name}」改为「${SCOPE_LABEL.DEPT}」后，本部门以外的成员将无法看到该工具（已被单独定向授权者仍可下载）。确定变更吗？`
    : `将「${row.name}」改为「${SCOPE_LABEL.PUBLIC}」后，所有部门成员都能看到该工具。确定变更吗？`;

  try {
    await ElMessageBox.confirm(confirmText, '变更可见范围', {
      type: 'warning',
      confirmButtonText: '确定变更',
      cancelButtonText: '取消',
    });
  } catch {
    // 取消：不发请求、不改本地显示态
    return;
  }

  try {
    const detail = await getToolDetail(row.id);
    await updateTool(row.id, {
      name: detail.name,
      version: detail.latestVersion,
      intro: detail.intro,
      readme: detail.readme ?? undefined,
      fileExt: detail.fileExt,
      platform: detail.platform ?? undefined,
      dependency: detail.dependency ?? undefined,
      tagIds: detail.tags.map((tag) => tag.id),
      scope,
    });
    ElMessage.success(`「${row.name}」可见范围已更新为「${SCOPE_LABEL[scope]}」`);
    await fetchList();
  } catch (cause) {
    // 失败：仅提示，保留原显示态（不产生"看起来改了其实没改"）
    ElMessage.error(cause instanceof Error ? cause.message : '可见范围变更失败，请稍后重试');
  }
}

function handleEmptyAction(): void {
  if (filtering.value) {
    handleReset();
    return;
  }
  void router.push({ name: 'tool-upload' });
}

function handlePageChange(page: number): void {
  pageNum.value = page;
  void fetchList();
}

function handleSizeChange(size: number): void {
  pageSize.value = normalizePageSize(size);
  pageNum.value = 1;
  void fetchList();
}

/**
 * v1.17：收到 `GRANT_CHANGED` → **重新拉取列表**（定向授权是查库判定，刷新即生效）。
 * ⚠️ 与"被踢下线"完全独立：**不登出、不跳登录页**。
 */
function handleGrantChanged(): void {
  ElMessage.info('你的可下载工具已更新');
  void fetchList();
}

onMounted(() => {
  void fetchTags();
  void fetchList();
  ws.onGrantChanged(handleGrantChanged);
});

onUnmounted(() => {
  ws.onGrantChanged(null);
});
</script>

<template>
  <div class="tb-page">
    <ToolFilterBar
      :filters="filters"
      :tags="tags"
      :tags-hint="tagsError"
      @search="handleSearch"
      @reset="handleReset"
    >
      <template #actions>
        <!-- 多选后出现在"重置"旁：批量删除（软删除→回收站） -->
        <el-button v-if="canDelete && selectedRows.length" type="danger" plain @click="handleBatchDelete">
          批量删除（{{ selectedRows.length }}）
        </el-button>
      </template>
    </ToolFilterBar>

    <div class="tb-page__toolbar">
      <span class="tb-muted">共 {{ total }} 个工具</span>
      <el-button v-if="userStore.has(PERMISSION.TOOL_UPLOAD)" type="primary" @click="router.push({ name: 'tool-upload' })">
        上传工具
      </el-button>
    </div>

    <PageContainer
      :loading="loading"
      :error="error"
      :empty="isEmpty"
      :empty-text="emptyText"
      :empty-hint="emptyHint"
      :empty-action-text="emptyActionText"
      @retry="fetchList"
      @empty-action="handleEmptyAction"
    >
      <ToolTable
        :list="list"
        :selectable="canDelete"
        @downloaded="fetchList"
        @delete="handleDelete"
        @grant="openGrant"
        @scope-change="handleScopeChange"
        @selection-change="handleSelectionChange"
      />

      <div class="tb-page__pager">
        <el-pagination
          :current-page="pageNum"
          :page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </PageContainer>

    <ToolGrantDialog
      v-model="grantVisible"
      :tool="grantTool"
      :submitting="grantSubmitting"
      :error-message="grantError"
      @save="handleSaveGrants"
    />
  </div>
</template>

<style scoped>
.tb-page {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap);
}

.tb-page__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tb-page__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--tb-gap);
}
</style>
