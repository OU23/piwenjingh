<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { BizError } from '@/api/request';
import { getInitStatus, initSystem } from '@/api/system';
import { BizCode } from '@/api/types';
import { validateInitForm } from '@/utils/initValidation';

/**
 * 系统初始化：创建首个全局管理员
 * 已初始化则直接跳登录页，不展示表单（见 docs/plan/P2 T1）
 */
const router = useRouter();

const checking = ref(true);
const statusError = ref('');
const errorMessage = ref('');
const submitting = ref(false);
/** 已初始化：不再渲染表单，直接引导去登录（避免表单闪现） */
const alreadyInitialized = ref(false);

const form = reactive({
  adminUsername: '',
  adminPassword: '',
  confirmPassword: '',
  adminRealName: '',
});

/** 账号规则等校验见 src/utils/initValidation.ts */

async function checkStatus(): Promise<void> {
  checking.value = true;
  statusError.value = '';
  try {
    const status = await getInitStatus();
    if (status.initialized) {
      alreadyInitialized.value = true;
      await router.replace({ name: 'login' });
    }
  } catch (cause) {
    statusError.value = cause instanceof Error ? cause.message : '无法获取初始化状态，请稍后重试';
  } finally {
    checking.value = false;
  }
}

function validate(): string {
  return validateInitForm(form);
}

async function handleSubmit(): Promise<void> {
  const invalid = validate();
  if (invalid) {
    errorMessage.value = invalid;
    return;
  }

  submitting.value = true;
  errorMessage.value = '';

  try {
    await initSystem({
      adminUsername: form.adminUsername.trim(),
      adminPassword: form.adminPassword,
      adminRealName: form.adminRealName.trim(),
    });
    ElMessage.success('初始化完成，请使用管理员账号登录');
    await router.replace({ name: 'login' });
  } catch (cause) {
    // 并发或重复提交：系统已初始化时不再停留本页
    if (cause instanceof BizError && cause.code === BizCode.ALREADY_INITIALIZED) {
      ElMessage.warning('系统已初始化，请直接登录');
      await router.replace({ name: 'login' });
      return;
    }
    errorMessage.value = cause instanceof Error ? cause.message : '初始化失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  void checkStatus();
});
</script>

<template>
  <div class="tb-init">
    <div class="tb-init__card">
      <div class="tb-init__head">
        <div class="tb-init__title">系统初始化</div>
        <div class="tb-muted">首次使用需创建管理员账号，完成后即可登录使用</div>
      </div>

      <div v-if="checking" class="tb-muted">正在检查初始化状态…</div>

      <div v-else-if="alreadyInitialized" class="tb-muted">系统已初始化，正在前往登录页…</div>

      <template v-else-if="statusError">
        <div class="tb-note is-danger">{{ statusError }}</div>
        <el-button class="tb-init__submit" @click="checkStatus">重新检查</el-button>
      </template>

      <template v-else>
        <label class="tb-init__label" for="init-username">管理员账号</label>
        <el-input id="init-username" v-model="form.adminUsername" placeholder="4–32 位字母、数字或下划线" />

        <label class="tb-init__label" for="init-realname">管理员姓名</label>
        <el-input id="init-realname" v-model="form.adminRealName" placeholder="请输入姓名" />

        <label class="tb-init__label" for="init-password">密码</label>
        <el-input id="init-password" v-model="form.adminPassword" type="password" show-password placeholder="6–32 位" />

        <label class="tb-init__label" for="init-confirm">确认密码</label>
        <el-input
          id="init-confirm"
          v-model="form.confirmPassword"
          type="password"
          show-password
          placeholder="请再次输入密码"
          @keyup.enter="handleSubmit"
        />

        <div v-if="errorMessage" class="tb-note is-danger tb-init__error">{{ errorMessage }}</div>

        <el-button class="tb-init__submit" type="primary" :loading="submitting" @click="handleSubmit">
          完成初始化
        </el-button>

        <div class="tb-note is-info tb-init__tip">初始化仅可执行一次；管理员账号将自动归入默认部门并拥有全部权限。</div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.tb-init {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--tb-bg);
}

.tb-init__card {
  width: 400px;
  background: var(--tb-panel);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius-lg);
  padding: var(--tb-gap-xl);
}

.tb-init__head {
  text-align: center;
  margin-bottom: var(--tb-gap-xl);
}

.tb-init__title {
  font-size: var(--tb-font-lg);
  font-weight: 700;
  margin-bottom: var(--tb-gap-xs);
}

.tb-init__label {
  display: block;
  font-size: var(--tb-font-xs);
  color: var(--tb-text-2);
  margin: 0 0 var(--tb-gap-xs);
}

.tb-init__error {
  margin: var(--tb-gap) 0 0;
}

.tb-init__submit {
  width: 100%;
  margin-top: var(--tb-gap-lg);
}

.tb-init__tip {
  margin-top: var(--tb-gap-lg);
}
</style>
