<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import BatchPermissionDialog from '@/components/BatchPermissionDialog.vue';
import { batchSaveUserPermissions, batchUpdateUserStatus, type UserVO } from '@/api/user';
import { STATUS } from '@/constants/enums';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';

/**
 * 用户管理的**批量操作入口**（P13 / 需求 9；契约 v1.16 §六）。
 *
 * 位置：筛选栏「重置」旁；随多选态出现/消失（`users` 为空即不渲染）。
 * 权限：批量停用需 `user:manage`、批量分配权限需 `user:grant`（后端判权为权威，R5）。
 *
 * ⚠️ **剔除当前登录人自己**（需求 9 明确要求；同时满足需求 10「不能给自己分配权限」，
 *    避免批量路径绕过单条入口的约束）。剔除后为空 → 提示并不发请求。
 *
 * 抽成独立组件的原因：`UserView.vue` 已 484 行（红线 500），批量逻辑一律落子组件，禁止堆行。
 */
const props = defineProps<{ users: UserVO[] }>();
const emit = defineEmits<{ done: [] }>();

const userStore = useUserStore();

const canManage = computed(() => userStore.has(PERMISSION.USER_MANAGE));
const canGrant = computed(() => userStore.has(PERMISSION.USER_GRANT));

const isSelf = (row: UserVO): boolean => row.id === userStore.user?.userId;
/** 选中集合里是否含自己（仅用于提示文案） */
const excludedSelf = computed(() => props.users.some(isSelf));
/** 实际可操作目标 = 选中集合 **剔除自己** */
const targets = computed(() => props.users.filter((row) => !isSelf(row)));

const submitting = ref(false);
const permVisible = ref(false);
const permSubmitting = ref(false);
const permError = ref('');

/** 批量停用：二次确认（说明影响面 + 是否已排除自己）→ 提交 → 刷新 */
async function handleBatchDisable(): Promise<void> {
  const rows = targets.value;
  if (rows.length === 0) {
    ElMessage.warning('请至少选择一个他人账号（当前登录账号不能停用）');
    return;
  }

  const excludedHint = excludedSelf.value ? '（已排除你自己）' : '';
  try {
    await ElMessageBox.confirm(
      `确定停用这 ${rows.length} 个账号吗？停用后其将立即无法登录。${excludedHint}`,
      '批量停用',
      { type: 'warning', confirmButtonText: '确定停用', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  submitting.value = true;
  try {
    await batchUpdateUserStatus({ userIds: rows.map((row) => row.id), status: STATUS.DISABLED });
    ElMessage.success(`已停用 ${rows.length} 个账号${excludedHint}`);
    emit('done');
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '批量停用失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

function openPermissions(): void {
  if (targets.value.length === 0) {
    ElMessage.warning('请至少选择一个他人账号（不能给自己分配权限）');
    return;
  }
  permError.value = '';
  permVisible.value = true;
}

/** 弹窗「确定」：**覆盖式**提交最终权限全集；失败就地提示、弹窗保留便于重试 */
async function handleSavePermissions(payload: { codes: string[] }): Promise<void> {
  const rows = targets.value;
  if (rows.length === 0 || permSubmitting.value) {
    return;
  }

  permSubmitting.value = true;
  permError.value = '';
  try {
    await batchSaveUserPermissions({ userIds: rows.map((row) => row.id), permissions: payload.codes });
    ElMessage.success(`已覆盖 ${rows.length} 个账号的权限；相关账号重新登录或刷新后生效`);
    permVisible.value = false;
    emit('done');
  } catch (cause) {
    permError.value = cause instanceof Error ? cause.message : '批量分配权限失败，请稍后重试';
  } finally {
    permSubmitting.value = false;
  }
}
</script>

<template>
  <el-button v-if="canManage && props.users.length" :loading="submitting" @click="handleBatchDisable">
    批量停用
  </el-button>
  <el-button v-if="canGrant && props.users.length" @click="openPermissions">批量分配权限</el-button>

  <BatchPermissionDialog
    v-model="permVisible"
    :count="targets.length"
    :submitting="permSubmitting"
    :error-message="permError"
    @save="handleSavePermissions"
  />
</template>
