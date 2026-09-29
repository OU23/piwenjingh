<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageContainer from '@/components/PageContainer.vue';
import ImportDialog from '@/components/ImportDialog.vue';
import { saveBlob } from '@/api/request';
import {
  createDept,
  downloadDeptTemplate,
  exportDepts,
  importDepts,
  listDepts,
  updateDept,
  updateDeptStatus,
  type DeptVO,
} from '@/api/dept';
import { STATUS, STATUS_LABEL } from '@/constants/enums';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import { validateDeptForm } from '@/utils/deptValidation';
import { formatDateTime } from '@/utils/format';

/**
 * 部门管理（契约 §四，计划 T3）
 * 部门为扁平结构；**停用前先确认，失败原因就地展示**（如"部门下仍有用户"），开关状态以服务端为准。
 */
const userStore = useUserStore();

const list = ref<DeptVO[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);

const dialogVisible = ref(false);
const editing = ref<DeptVO | null>(null);
const form = reactive({ name: '', remark: '' });
const formError = ref('');
const submitting = ref(false);

const canManage = computed(() => userStore.has(PERMISSION.DEPT_MANAGE));
const isEmpty = computed(() => !loading.value && !error.value && list.value.length === 0);
const dialogTitle = computed(() => (editing.value ? '编辑部门' : '新建部门'));

/** 批量导入 / 导出（契约 §6.2，v1.9 新增） */
const importVisible = ref(false);
const exporting = ref(false);

async function fetchList(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    list.value = await listDepts();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '部门列表加载失败，请稍后重试';
    list.value = [];
  } finally {
    loading.value = false;
  }
}

function openCreate(): void {
  editing.value = null;
  form.name = '';
  form.remark = '';
  formError.value = '';
  dialogVisible.value = true;
}

function openEdit(row: DeptVO): void {
  editing.value = row;
  form.name = row.name;
  form.remark = row.remark ?? '';
  formError.value = '';
  dialogVisible.value = true;
}

async function handleSubmit(): Promise<void> {
  const invalid = validateDeptForm(form);
  formError.value = invalid;
  if (invalid || submitting.value) {
    return;
  }

  submitting.value = true;
  try {
    const payload = { name: form.name.trim(), remark: form.remark.trim() || undefined };
    if (editing.value) {
      await updateDept(editing.value.id, payload);
      ElMessage.success('部门已更新');
    } else {
      await createDept(payload);
      ElMessage.success('部门已创建');
    }
    dialogVisible.value = false;
    await fetchList();
  } catch (cause) {
    // 重名等失败：就地展示后端原因，保留表单
    formError.value = cause instanceof Error ? cause.message : '保存失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

/** 启用 / 停用：用受控开关（:model-value），失败时开关自然保持原状，不会出现"界面已切换但服务端未变" */
async function handleToggle(row: DeptVO, next: boolean): Promise<void> {
  const action = next ? '启用' : '停用';

  try {
    await ElMessageBox.confirm(`确定${action}部门「${row.name}」吗？`, `${action}部门`, {
      type: 'warning',
      confirmButtonText: `确定${action}`,
      cancelButtonText: '取消',
    });
  } catch {
    return;
  }

  try {
    await updateDeptStatus(row.id, { status: next ? STATUS.ENABLED : STATUS.DISABLED });
    ElMessage.success(`已${action}部门「${row.name}」`);
    await fetchList();
  } catch (cause) {
    // 如"部门下仍有用户，不允许停用"：原因由后端给出，开关维持原状
    ElMessage.error(cause instanceof Error ? cause.message : `${action}失败，请稍后重试`);
  }
}

/** 下载导入模板（后端文件流 → 走既有**鉴权下载通道**落盘，非静态直链） */
async function handleDownloadTemplate(): Promise<void> {
  try {
    const payload = await downloadDeptTemplate();
    saveBlob(payload.blob, payload.fileName);
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '模板下载失败，请稍后重试');
  }
}

/** 导出部门（Excel 文件流）；部门列表无筛选条件，故导出全量 */
async function handleExport(): Promise<void> {
  if (exporting.value) {
    return;
  }

  exporting.value = true;
  try {
    const payload = await exportDepts();
    saveBlob(payload.blob, payload.fileName);
    ElMessage.success('导出已开始');
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '导出失败，请稍后重试');
  } finally {
    exporting.value = false;
  }
}

/** 导入结束且有成功行 → 刷新列表（否则界面与库不一致） */
function handleImported(): void {
  void fetchList();
}

onMounted(() => {
  void fetchList();
});
</script>

<template>
  <div class="tb-page">
    <div class="tb-toolbar">
      <span class="tb-muted">共 {{ list.length }} 个部门</span>
      <div class="tb-toolbar__ops">
        <el-button v-if="canManage" type="primary" @click="openCreate">新建部门</el-button>
        <el-button v-if="canManage" @click="handleDownloadTemplate">下载模板</el-button>
        <el-button v-if="canManage" @click="importVisible = true">导入</el-button>
        <el-button v-if="canManage" :loading="exporting" @click="handleExport">导出</el-button>
      </div>
    </div>

    <PageContainer
      :loading="loading"
      :error="error"
      :empty="isEmpty"
      empty-text="还没有任何部门"
      empty-hint="部门是账号归属与工具可见范围的依据，建议先建好部门"
      @retry="fetchList"
    >
      <el-table :data="list" row-key="id" border stripe>
        <el-table-column prop="name" label="部门名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="备注" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column prop="userCount" label="人数" width="90" />
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === STATUS.ENABLED"
              :disabled="!canManage"
              inline-prompt
              :active-text="STATUS_LABEL[STATUS.ENABLED]"
              :inactive-text="STATUS_LABEL[STATUS.DISABLED]"
              @change="(value: unknown) => handleToggle(row, Boolean(value))"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canManage" link type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageContainer>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="440px" :close-on-click-modal="false">
      <div class="tb-form">
        <div class="tb-form__item">
          <label class="tb-form__label" for="dept-name">部门名称 <em>*</em></label>
          <el-input id="dept-name" v-model="form.name" placeholder="如：研发部（不可重名）" />
        </div>
        <div class="tb-form__item">
          <label class="tb-form__label" for="dept-remark">备注</label>
          <el-input id="dept-remark" v-model="form.remark" type="textarea" :rows="2" placeholder="选填" />
        </div>
      </div>

      <div v-if="formError" class="tb-note is-danger">{{ formError }}</div>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>

    <ImportDialog
      v-model="importVisible"
      title="Excel 导入 · 部门"
      template-name="部门导入模板.xlsx"
      :download-template="handleDownloadTemplate"
      :submit="importDepts"
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

.tb-form {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap);
}

.tb-form__item {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap-xs);
}

.tb-form__label {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}

.tb-form__label em {
  color: var(--tb-danger);
  font-style: normal;
}
</style>
