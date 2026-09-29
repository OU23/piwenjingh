<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageContainer from '@/components/PageContainer.vue';
import { clearDeletedTools, listDeletedTools, purgeTools, restoreTool, type ToolVO } from '@/api/tool';
import { SCOPE_LABEL, type ScopeValue } from '@/constants/enums';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import { formatDateTime, formatFileSize } from '@/utils/format';
import { normalizePageSize } from '@/utils/toolQuery';

/**
 * 已删除工具（回收站，契约 §9.6 / v1.5）
 * - 恢复：软删除记录回到原状态（工具与全部版本）；
 * - 彻底删除 / 清空：**物理删除**（含磁盘文件），不可恢复。
 */
const userStore = useUserStore();

const keyword = ref('');
const pageNum = ref(1);
const pageSize = ref(20);
const list = ref<ToolVO[]>([]);
const total = ref(0);
const loading = ref(false);
const error = ref<string | null>(null);
/** 当前多选行（用于批量彻底删除） */
const selectedRows = ref<ToolVO[]>([]);

/** 恢复 / 彻底删除 / 清空 同属 `tool:delete` 权限 */
const canDelete = computed(() => userStore.has(PERMISSION.TOOL_DELETE));
const filtering = computed(() => Boolean(keyword.value.trim()));
const isEmpty = computed(() => !loading.value && !error.value && list.value.length === 0);

function scopeLabel(scope: string): string {
  return SCOPE_LABEL[scope as ScopeValue] ?? scope;
}

async function fetchList(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const result = await listDeletedTools({
      pageNum: pageNum.value,
      pageSize: normalizePageSize(pageSize.value),
      keyword: keyword.value.trim() || undefined,
    });
    list.value = result.list;
    total.value = result.total;

    if (result.list.length === 0 && pageNum.value > 1) {
      pageNum.value = 1;
      await fetchList();
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '已删除列表加载失败，请稍后重试';
    list.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

function handleSearch(): void {
  pageNum.value = 1;
  void fetchList();
}

function handleReset(): void {
  keyword.value = '';
  handleSearch();
}

async function handleRestore(row: ToolVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定恢复「${row.name}」吗？该工具及其全部版本将回到原状态，并从本列表移除。`,
      '恢复工具',
      { type: 'warning', confirmButtonText: '确定恢复', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  try {
    await restoreTool(row.id);
    ElMessage.success(`「${row.name}」已恢复到工具列表`);
    await fetchList();
  } catch (cause) {
    // 如"记录不存在 / 未删除"：展示后端原因，列表状态不变
    ElMessage.error(cause instanceof Error ? cause.message : '恢复失败，请稍后重试');
  }
}

function handleSelectionChange(rows: ToolVO[]): void {
  selectedRows.value = rows;
}

/** 彻底删除单个：物理删除（含磁盘文件），不可恢复 */
async function handlePurge(row: ToolVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定彻底删除「${row.name}」吗？将删除其全部版本与磁盘文件，不可恢复。`,
      '彻底删除',
      { type: 'warning', confirmButtonText: '确定彻底删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  try {
    await purgeTools([row.id]);
    ElMessage.success(`「${row.name}」已彻底删除`);
    await fetchList();
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '彻底删除失败，请稍后重试');
  }
}

/** 批量彻底删除：物理删除（含磁盘文件），不可恢复 */
async function handleBatchPurge(): Promise<void> {
  const rows = selectedRows.value;
  if (rows.length === 0) {
    return;
  }

  try {
    await ElMessageBox.confirm(
      `确定彻底删除选中的 ${rows.length} 个工具吗？将删除其全部版本与磁盘文件，不可恢复。`,
      '批量彻底删除',
      { type: 'warning', confirmButtonText: '确定彻底删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  try {
    await purgeTools(rows.map((item) => item.id));
    ElMessage.success(`已彻底删除 ${rows.length} 个工具`);
    selectedRows.value = [];
    await fetchList();
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '批量彻底删除失败，请稍后重试');
  }
}

/** 清空回收站：彻底删除全部已删除工具（含磁盘文件），不可恢复 */
async function handleClearAll(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定清空回收站吗？将彻底删除全部已删除工具（当前 ${total.value} 个）及其磁盘文件，不可恢复。`,
      '清空回收站',
      { type: 'warning', confirmButtonText: '确定清空', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  try {
    await clearDeletedTools();
    ElMessage.success('回收站已清空');
    selectedRows.value = [];
    await fetchList();
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '清空失败，请稍后重试');
  }
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

onMounted(() => {
  void fetchList();
});
</script>

<template>
  <div class="tb-page">
    <div class="tb-filter">
      <div class="tb-filter__item is-wide">
        <span class="tb-filter__label">关键词</span>
        <el-input v-model="keyword" placeholder="工具名称" clearable @keyup.enter="handleSearch" @clear="handleSearch" />
      </div>
      <div class="tb-filter__actions">
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
        <el-button v-if="canDelete" type="danger" plain @click="handleClearAll">清空</el-button>
        <!-- 多选后出现：批量彻底删除（物理删除，不可恢复） -->
        <el-button v-if="canDelete && selectedRows.length" type="danger" @click="handleBatchPurge">
          批量删除（{{ selectedRows.length }}）
        </el-button>
      </div>
    </div>

    <div class="tb-muted">共 {{ total }} 个已删除工具</div>

    <PageContainer
      :loading="loading"
      :error="error"
      :empty="isEmpty"
      :empty-text="filtering ? '没有匹配的已删除工具' : '已删除列表是空的'"
      :empty-hint="filtering ? '试试更换关键词' : '在工具列表删除的工具会出现在这里，可随时恢复'"
      :empty-action-text="filtering ? '重置筛选' : ''"
      @retry="fetchList"
      @empty-action="handleReset"
    >
      <el-table :data="list" row-key="id" border stripe @selection-change="handleSelectionChange">
        <el-table-column v-if="canDelete" type="selection" width="46" />
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="latestVersion" label="版本" width="90" />
        <el-table-column label="格式" width="90">
          <template #default="{ row }">{{ row.fileExt ? `.${row.fileExt}` : '—' }}</template>
        </el-table-column>
        <el-table-column prop="deptName" label="归属部门" width="120" show-overflow-tooltip />
        <el-table-column label="范围" width="110">
          <template #default="{ row }">{{ scopeLabel(row.scope) }}</template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="最近更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canDelete" link type="primary" @click="handleRestore(row)">恢复</el-button>
            <el-button v-if="canDelete" link type="danger" @click="handlePurge(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="tb-pager">
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
  </div>
</template>

<style scoped>
.tb-page {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap);
}

.tb-filter {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--tb-gap);
  padding: var(--tb-gap);
  background: var(--tb-panel-2);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
}

.tb-filter__item.is-wide {
  width: 260px;
}

.tb-filter__label {
  display: block;
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
  margin-bottom: var(--tb-gap-xs);
}

.tb-filter__actions {
  display: flex;
  gap: var(--tb-gap-sm);
}

.tb-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--tb-gap);
}
</style>
