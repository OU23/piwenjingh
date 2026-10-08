<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageContainer from '@/components/PageContainer.vue';
import ImportDialog from '@/components/ImportDialog.vue';
import PermissionDialog from '@/components/PermissionDialog.vue';
import UserBatchActions from '@/components/UserBatchActions.vue';
import UserFormDialog from '@/components/UserFormDialog.vue';
import { saveBlob } from '@/api/request';
import { listDepts, type DeptVO } from '@/api/dept';
import {
  createUser,
  downloadUserTemplate,
  exportUsers,
  importUsers,
  listUsers,
  saveUserPermissions,
  saveUserToolGrants,
  updateUser,
  updateUserStatus,
  type UserVO,
} from '@/api/user';
import type { ID } from '@/api/types';
import { PAGE_SIZE_DEFAULT, STATUS, STATUS_LABEL } from '@/constants/enums';
import { DEFAULT_NEW_USER_PERMISSIONS, PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import { formatDateTime } from '@/utils/format';
import { normalizePageSize } from '@/utils/toolQuery';

/**
 * 用户管理（契约 §六，计划 T1 / T2）
 * 新建 / 编辑账号与权限分配都在本页完成（待确认清单 Q-06 的结论）。
 */
const userStore = useUserStore();

const filters = reactive({ keyword: '', deptId: '' });
const pageNum = ref(1);
const pageSize = ref(PAGE_SIZE_DEFAULT);
const list = ref<UserVO[]>([]);
const total = ref(0);
const loading = ref(false);
const error = ref<string | null>(null);
const depts = ref<DeptVO[]>([]);
const deptError = ref('');

const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const editingUser = ref<UserVO | null>(null);
const formSubmitting = ref(false);
const formError = ref('');

const permVisible = ref(false);
const permUser = ref<UserVO | null>(null);
const permSubmitting = ref(false);
const permError = ref('');

/** 批量导入 / 导出（契约 §6.2，v1.9 新增） */
const importVisible = ref(false);
const exporting = ref(false);

/** 当前多选行（P13 / 需求 9：批量停用 · 批量分配权限，交给 `UserBatchActions` 处理） */
const selectedUsers = ref<UserVO[]>([]);

function handleSelectionChange(rows: UserVO[]): void {
  selectedUsers.value = rows;
}

const canManage = computed(() => userStore.has(PERMISSION.USER_MANAGE));
const canGrant = computed(() => userStore.has(PERMISSION.USER_GRANT));
/** 该行是否为当前登录账号：用于禁用"停用自己"（与服务端「不得停用当前登录账号自身」一致） */
const isSelf = (row: UserVO): boolean => row.id === userStore.user?.userId;
const filtering = computed(() => Boolean(filters.keyword.trim() || filters.deptId));
const isEmpty = computed(() => !loading.value && !error.value && list.value.length === 0);

async function fetchList(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const result = await listUsers({
      pageNum: pageNum.value,
      pageSize: normalizePageSize(pageSize.value),
      keyword: filters.keyword.trim() || undefined,
      deptId: filters.deptId || undefined,
    });
    list.value = result.list;
    total.value = result.total;

    if (result.list.length === 0 && pageNum.value > 1) {
      pageNum.value = 1;
      await fetchList();
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '账号列表加载失败，请稍后重试';
    list.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
    // 表格因 loading 切换会被 PageContainer 卸载重建，重建后视觉勾选必然丢失；
    // 同步清空多选态，避免批量按钮因僵尸 selectedUsers 不消失（同 P12 Bug1 口径）
    selectedUsers.value = [];
  }
}

async function fetchDepts(): Promise<void> {
  deptError.value = '';
  try {
    depts.value = await listDepts();
  } catch (cause) {
    // 部门字典加载失败不阻断账号列表，但必须可见（验收 A3：无静默失败）
    depts.value = [];
    deptError.value = cause instanceof Error ? cause.message : '部门列表加载失败';
  }
}

function handleSearch(): void {
  pageNum.value = 1;
  void fetchList();
}

function handleReset(): void {
  filters.keyword = '';
  filters.deptId = '';
  handleSearch();
}

function openCreate(): void {
  formMode.value = 'create';
  editingUser.value = null;
  // 部门字典未就绪时先说明：建号必须选部门，否则用户会对着空下拉发懵
  formError.value = deptError.value ? `${deptError.value}，请关闭弹窗后重试` : '';
  formVisible.value = true;
}

function openEdit(row: UserVO): void {
  formMode.value = 'edit';
  editingUser.value = row;
  formError.value = deptError.value ? `${deptError.value}，请关闭弹窗后重试` : '';
  formVisible.value = true;
}

async function handleFormSubmit(payload: {
  username: string;
  realName: string;
  password: string;
  deptId: string;
}): Promise<void> {
  if (formSubmitting.value) {
    return;
  }

  formSubmitting.value = true;
  formError.value = '';

  try {
    if (formMode.value === 'create') {
      await createUser({
        username: payload.username.trim(),
        realName: payload.realName.trim(),
        password: payload.password,
        deptId: payload.deptId,
        // 新建账号默认**仅授予「下载」**（需求方 2026-09-24 确认：不再默认给上传），
        // 避免建完即"什么都做不了"；管理员可在「分配权限」中按需增加
        permissions: [...DEFAULT_NEW_USER_PERMISSIONS],
      });
      ElMessage.success('账号已创建，已默认授予「下载」权限，可在「分配权限」中调整');
    } else if (editingUser.value) {
      await updateUser(editingUser.value.id, { realName: payload.realName.trim(), deptId: payload.deptId });
      ElMessage.success('账号已更新');
    }

    formVisible.value = false;
    await fetchList();
  } catch (cause) {
    // 失败保留表单内容，就地展示原因（验收 A3）
    formError.value = cause instanceof Error ? cause.message : '保存失败，请稍后重试';
  } finally {
    formSubmitting.value = false;
  }
}

function openPermissions(row: UserVO): void {
  permUser.value = row;
  permError.value = '';
  permVisible.value = true;
}

/**
 * 保存权限：**两个接口串行提交**（契约 §6.1，v1.7）
 * ① `PUT /api/users/{id}/permissions`（既有，签名不变）
 * ② `PUT /api/users/{id}/tool-grants`（跨部门授权，全量覆盖）
 * - ① 失败 → 就地提示、**不发起** ②（避免"权限没存上、授权却变了"）
 * - ② 失败 → 提示"权限已保存，但额外授权保存失败"，**弹窗保留**便于重试
 * - `payload.toolIds === null`（授权面板未展开）→ 跳过 ②，避免把已有授权清空
 * - 两者各自独立事务，**不做跨接口事务**（数据不冲突，中间态窗口极小）
 */
async function handleSavePermissions(payload: { codes: string[]; toolIds: ID[] | null }): Promise<void> {
  if (!permUser.value || permSubmitting.value) {
    return;
  }

  const userId = permUser.value.id;
  permSubmitting.value = true;
  permError.value = '';

  try {
    await saveUserPermissions(userId, { permissions: payload.codes });
  } catch (cause) {
    permError.value = cause instanceof Error ? cause.message : '权限保存失败，请稍后重试';
    permSubmitting.value = false;
    return;
  }

  try {
    if (payload.toolIds !== null) {
      await saveUserToolGrants(userId, { toolIds: payload.toolIds });
    }
    ElMessage.success('权限已保存；该账号重新登录或刷新后生效');
    permVisible.value = false;
    await fetchList();
  } catch (cause) {
    const reason = cause instanceof Error ? cause.message : '请稍后重试';
    permError.value = `权限已保存，但额外授权保存失败：${reason}`;
  } finally {
    permSubmitting.value = false;
  }
}

/** 启用 / 停用账号：二次确认后调接口，状态以服务端为准；失败就地展示原因 */
async function handleToggleStatus(row: UserVO): Promise<void> {
  const next = row.status === STATUS.ENABLED ? STATUS.DISABLED : STATUS.ENABLED;
  const action = next === STATUS.ENABLED ? '启用' : '停用';
  const name = row.realName || row.username;
  const consequence = next === STATUS.DISABLED ? '（停用后该账号将无法登录）' : '';

  try {
    await ElMessageBox.confirm(`确定${action}账号「${name}」吗？${consequence}`, `${action}账号`, {
      type: 'warning',
      confirmButtonText: `确定${action}`,
      cancelButtonText: '取消',
    });
  } catch {
    return;
  }

  try {
    await updateUserStatus(row.id, { status: next });
    ElMessage.success(`已${action}账号「${name}」`);
    await fetchList();
  } catch (cause) {
    // 如"不得停用当前登录账号自身"：原因由后端给出，列表状态不变
    ElMessage.error(cause instanceof Error ? cause.message : `${action}失败，请稍后重试`);
  }
}

/** 下载导入模板（后端文件流 → 走既有**鉴权下载通道**落盘，非静态直链） */
async function handleDownloadTemplate(): Promise<void> {
  try {
    const payload = await downloadUserTemplate();
    saveBlob(payload.blob, payload.fileName);
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '模板下载失败，请稍后重试');
  }
}

/** 导出用户（Excel 文件流）；**带上当前列表的筛选条件**，导出结果与所见一致 */
async function handleExport(): Promise<void> {
  if (exporting.value) {
    return;
  }

  exporting.value = true;
  try {
    const payload = await exportUsers({
      keyword: filters.keyword.trim() || undefined,
      deptId: filters.deptId || undefined,
    });
    saveBlob(payload.blob, payload.fileName);
    ElMessage.success('导出已开始');
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '导出失败，请稍后重试');
  } finally {
    exporting.value = false;
  }
}

/** 导入结束且有成功行 → 刷新账号列表；**部门字典也要刷新**（用户导入可自动新建部门） */
function handleImported(): void {
  void fetchDepts();
  void fetchList();
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
  void fetchDepts();
  void fetchList();
});
</script>

<template>
  <div class="tb-page">
    <div class="tb-filter">
      <div class="tb-filter__item is-wide">
        <span class="tb-filter__label">关键词</span>
        <el-input v-model="filters.keyword" placeholder="账号或姓名" clearable @keyup.enter="handleSearch" @clear="handleSearch" />
      </div>
      <div class="tb-filter__item">
        <span class="tb-filter__label">归属部门</span>
        <el-select v-model="filters.deptId" placeholder="全部部门" clearable @change="handleSearch">
          <el-option v-for="dept in depts" :key="dept.id" :label="dept.name" :value="dept.id" />
        </el-select>
        <span v-if="deptError" class="tb-filter__hint">
          {{ deptError }}（暂不能按部门筛选）
          <el-button link type="primary" size="small" @click="fetchDepts">重试</el-button>
        </span>
      </div>
      <div class="tb-filter__actions">
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
        <!-- 批量操作（P13 / 需求 9）：多选后出现在「重置」旁，逻辑在同名子组件内 -->
        <UserBatchActions :users="selectedUsers" @done="fetchList" />
      </div>
    </div>

    <div class="tb-toolbar">
      <span class="tb-muted">共 {{ total }} 个账号</span>
      <div class="tb-toolbar__ops">
        <el-button v-if="canManage" type="primary" @click="openCreate">新建账号</el-button>
        <el-button v-if="canManage" @click="handleDownloadTemplate">下载模板</el-button>
        <el-button v-if="canManage" @click="importVisible = true">导入</el-button>
        <el-button v-if="canManage" :loading="exporting" @click="handleExport">导出</el-button>
      </div>
    </div>

    <PageContainer
      :loading="loading"
      :error="error"
      :empty="isEmpty"
      :empty-text="filtering ? '没有匹配的账号' : '还没有任何账号'"
      :empty-hint="filtering ? '试试更换关键词或清空筛选条件' : '点右上角「新建账号」为成员开通账号'"
      :empty-action-text="filtering ? '重置筛选' : ''"
      @retry="fetchList"
      @empty-action="handleReset"
    >
      <el-table :data="list" row-key="id" border stripe @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="username" label="账号" min-width="140" show-overflow-tooltip />
        <el-table-column prop="realName" label="姓名" min-width="120" show-overflow-tooltip />
        <el-table-column prop="deptName" label="归属部门" min-width="120" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ STATUS_LABEL[row.status] ?? '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <div class="tb-table__ops">
              <el-button v-if="canManage" link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="canGrant && !isSelf(row)" link type="primary" @click="openPermissions(row)">分配权限</el-button>
              <el-button
                v-if="canManage"
                link
                :type="row.status === STATUS.ENABLED ? 'danger' : 'primary'"
                :disabled="row.status === STATUS.ENABLED && isSelf(row)"
                :title="row.status === STATUS.ENABLED && isSelf(row) ? '不能停用当前登录账号' : ''"
                @click="handleToggleStatus(row)"
              >
                {{ row.status === STATUS.ENABLED ? '停用' : '启用' }}
              </el-button>
            </div>
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

    <UserFormDialog
      v-model="formVisible"
      :mode="formMode"
      :user="editingUser"
      :depts="depts"
      :submitting="formSubmitting"
      :error-message="formError"
      @submit="handleFormSubmit"
    />

    <PermissionDialog
      v-model="permVisible"
      :user="permUser"
      :submitting="permSubmitting"
      :error-message="permError"
      @save="handleSavePermissions"
    />

    <ImportDialog
      v-model="importVisible"
      title="Excel 导入 · 用户"
      template-name="用户导入模板.xlsx"
      :download-template="handleDownloadTemplate"
      :submit="importUsers"
      @imported="handleImported"
    />
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

.tb-filter__item {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap-xs);
  width: 200px;
}

.tb-filter__item.is-wide {
  width: 240px;
}

.tb-filter__label {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}

.tb-filter__hint {
  font-size: var(--tb-font-xs);
  line-height: 1.4;
  color: var(--tb-danger);
}

.tb-filter__actions {
  display: flex;
  gap: var(--tb-gap-sm);
}

.tb-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 工具栏操作区：「新建」在最前，其后依次是「下载模板 / 导入 / 导出」 */
.tb-toolbar__ops {
  display: flex;
  align-items: center;
  gap: var(--tb-gap-sm);
}

.tb-table__ops {
  display: flex;
  align-items: center;
  gap: var(--tb-gap-sm);
}

.tb-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--tb-gap);
}
</style>
