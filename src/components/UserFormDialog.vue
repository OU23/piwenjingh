<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import type { DeptVO } from '@/api/dept';
import type { UserVO } from '@/api/user';
import { validateUserCreate, validateUserUpdate } from '@/utils/userValidation';

/**
 * 新建 / 编辑账号弹窗（契约 §六）
 * 编辑模式**只允许改姓名与归属部门**（账号不可改），因此不展示密码与账号输入。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    mode?: 'create' | 'edit';
    user?: UserVO | null;
    depts: DeptVO[];
    submitting?: boolean;
    errorMessage?: string;
  }>(),
  { mode: 'create', user: null, submitting: false, errorMessage: '' },
);

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  submit: [payload: { username: string; realName: string; password: string; deptId: string }];
}>();

const form = reactive({ username: '', realName: '', password: '', deptId: '' });
const validationError = ref('');
/** 本地校验优先于后端错误；两者都为空时表示无错误 */
const localError = computed(() => validationError.value || props.errorMessage);

const title = computed(() => (props.mode === 'create' ? '新建账号' : '编辑账号'));

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

watch(
  () => [props.modelValue, props.user] as const,
  ([open, user]) => {
    if (!open) {
      return;
    }
    form.username = props.mode === 'create' ? '' : (user?.username ?? '');
    form.realName = user?.realName ?? '';
    form.password = '';
    form.deptId = user?.deptId ?? '';
  },
  { immediate: true },
);

function handleSubmit(): void {
  const invalid =
    props.mode === 'create'
      ? validateUserCreate(form)
      : validateUserUpdate({ realName: form.realName, deptId: form.deptId });

  validationError.value = invalid;
  if (invalid) {
    return;
  }
  emit('submit', { ...form });
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="460px" :close-on-click-modal="false">
    <div class="tb-form">
      <div class="tb-form__item">
        <label class="tb-form__label" for="user-username">账号 <em>*</em></label>
        <el-input
          id="user-username"
          v-model="form.username"
          :disabled="props.mode === 'edit'"
          placeholder="4–32 位字母、数字或下划线"
        />
        <span v-if="props.mode === 'edit'" class="tb-muted">账号创建后不可修改</span>
      </div>

      <div class="tb-form__item">
        <label class="tb-form__label" for="user-realname">姓名 <em>*</em></label>
        <el-input id="user-realname" v-model="form.realName" placeholder="请输入姓名" />
      </div>

      <div v-if="props.mode === 'create'" class="tb-form__item">
        <label class="tb-form__label" for="user-password">初始密码 <em>*</em></label>
        <el-input id="user-password" v-model="form.password" type="password" show-password placeholder="6–32 位" />
      </div>

      <div class="tb-form__item">
        <label class="tb-form__label" for="user-dept">归属部门 <em>*</em></label>
        <el-select id="user-dept" v-model="form.deptId" placeholder="请选择部门" style="width: 100%">
          <el-option v-for="dept in props.depts" :key="dept.id" :label="dept.name" :value="dept.id" />
        </el-select>
      </div>
    </div>

    <div v-if="localError" class="tb-note is-danger">{{ localError }}</div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="props.submitting" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
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
