<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox, type InputInstance } from 'element-plus';
import { Lock, User } from '@element-plus/icons-vue';
import { login } from '@/api/auth';
import { BizError } from '@/api/request';
import { getInitStatus } from '@/api/system';
import { BizCode } from '@/api/types';
import { useUserStore } from '@/stores/user';
import {
  clearRememberedAccount,
  readRememberedAccount,
  saveRememberedAccount,
} from '@/utils/rememberedAccount';

/**
 * 登录页：账号 + 密码（带前缀图标，参考若依登录页）
 * 账号由管理员创建，系统不提供自助注册；系统尚未初始化时给出初始化入口。
 *
 * 「记住密码」：进入时若本地存有账号密码则自动预填并勾选；登录成功后按勾选状态保存 / 清除。
 * 登出只清除登录态，**不清除**记住的账号密码（故退出后再进本页仍为预填状态）。
 */
const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const remembered = readRememberedAccount();
const form = reactive({ username: remembered?.username ?? '', password: remembered?.password ?? '' });
/** 默认勾选状态 = 是否已记住过账号（避免未授权时默认保存密码） */
const remember = ref<boolean>(!!remembered);
const errorMessage = ref('');
const submitting = ref(false);
const showInitEntry = ref(false);
/** 取消强制登录后把焦点送回密码框，便于直接改账号（方案 §7.4） */
const passwordRef = ref<InputInstance>();

function validate(): string {
  if (!form.username.trim()) {
    return '请输入账号';
  }
  if (!form.password) {
    return '请输入密码';
  }
  return '';
}

/**
 * 提交登录（M-18 / 契约 §3.1）：
 * 该账号已有活跃会话时后端返回 `1008`（HTTP 200 业务失败，走既有 BizError 分支）→ 弹「是否强制登录」；
 * **取消 → 关闭弹窗、不发起任何请求**（焦点回密码框）；**确定 → 复用已输入的账号密码带 `force: true` 重发**。
 */
async function handleSubmit(): Promise<void> {
  if (submitting.value) {
    return; // 防重复提交（含回车连击）
  }
  const invalid = validate();
  if (invalid) {
    errorMessage.value = invalid;
    return;
  }

  submitting.value = true;
  errorMessage.value = '';
  try {
    let force = false;
    for (;;) {
      try {
        // force 仅在强制登录时携带（缺省不传，见契约 §3.1）
        const result = await login({
          username: form.username.trim(),
          password: form.password,
          ...(force ? { force: true } : {}),
        });
        userStore.setLogin({ token: result.token, user: result.user });
        // 记住密码：勾选则保存，未勾选则清除（登出不影响，见文件头说明）
        if (remember.value) {
          saveRememberedAccount(form.username.trim(), form.password);
        } else {
          clearRememberedAccount();
        }
        ElMessage.success('登录成功');
        await redirectAfterLogin();
        return;
      } catch (error) {
        // 账号已在线且未请求强制登录 → 先确认，再决定是否重发
        if (error instanceof BizError && error.code === BizCode.ONLINE_CONFLICT && !force) {
          const confirmed = await confirmForceLogin();
          if (!confirmed) {
            passwordRef.value?.focus();
            return;
          }
          force = true;
          continue;
        }
        setLoginError(error);
        return;
      }
    }
  } finally {
    submitting.value = false;
  }
}

/** 「是否强制登录」确认框：取消（含 ESC / 右上角关闭）返回 false，且不发起任何请求 */
async function confirmForceLogin(): Promise<boolean> {
  try {
    await ElMessageBox.confirm(
      '该账号已在其他设备登录，继续登录将使该设备上的登录失效。是否强制登录？',
      '账号已登录',
      { confirmButtonText: '强制登录', cancelButtonText: '取消', type: 'warning' },
    );
    return true;
  } catch {
    return false;
  }
}

/** 登录成功后的跳转（支持 `?redirect=` 回跳） */
async function redirectAfterLogin(): Promise<void> {
  const redirect = route.query.redirect;
  if (typeof redirect === 'string' && redirect) {
    await router.replace(redirect);
    return;
  }
  await router.replace({ name: 'tool-list' });
}

/** 失败提示：账号不存在 / 密码错误 / 账号停用统一文案，避免账号探测（契约 §三） */
function setLoginError(error: unknown): void {
  if (error instanceof BizError && error.code === BizCode.LOGIN_FAILED) {
    errorMessage.value = '账号或密码错误';
    return;
  }
  errorMessage.value = error instanceof Error ? error.message : '账号或密码错误';
}

onMounted(async () => {
  try {
    const status = await getInitStatus();
    showInitEntry.value = !status.initialized;
  } catch {
    // 状态查询失败不影响登录流程，仅不展示初始化入口
    showInitEntry.value = false;
  }
});
</script>

<template>
  <div class="tb-login">
    <div class="tb-login__card">
      <div class="tb-login__head">
        <div class="tb-login__title">tool-box 工具管理箱</div>
        <div class="tb-muted">请使用管理员分配的账号登录</div>
      </div>

      <div class="tb-login__field">
        <label class="tb-login__label" for="login-username">账号</label>
        <el-input
          id="login-username"
          v-model="form.username"
          size="large"
          placeholder="请输入账号"
          @keyup.enter="handleSubmit"
        >
          <template #prefix>
            <el-icon><User /></el-icon>
          </template>
        </el-input>
      </div>

      <div class="tb-login__field">
        <label class="tb-login__label" for="login-password">密码</label>
        <el-input
          id="login-password"
          ref="passwordRef"
          v-model="form.password"
          size="large"
          type="password"
          show-password
          placeholder="请输入密码"
          @keyup.enter="handleSubmit"
        >
          <template #prefix>
            <el-icon><Lock /></el-icon>
          </template>
        </el-input>
      </div>

      <div v-if="errorMessage" class="tb-note is-danger tb-login__error">{{ errorMessage }}</div>

      <div class="tb-login__row">
        <el-checkbox id="login-remember" v-model="remember">记住密码</el-checkbox>
      </div>

      <el-button
        class="tb-login__submit"
        type="primary"
        size="large"
        :loading="submitting"
        @click="handleSubmit"
      >
        登 录
      </el-button>

      <div v-if="showInitEntry" class="tb-note is-info tb-login__init">
        系统尚未初始化，<router-link :to="{ name: 'init' }">前往初始化 →</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tb-login {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--tb-bg);
}

.tb-login__card {
  width: 430px;
  background: var(--tb-panel);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius-lg);
  padding: var(--tb-gap-xl);
}

.tb-login__head {
  text-align: center;
  margin-bottom: var(--tb-gap-xl);
}

.tb-login__title {
  font-size: var(--tb-font-xl);
  font-weight: 700;
  margin-bottom: var(--tb-gap-sm);
}

.tb-login__field {
  margin-bottom: var(--tb-gap-lg);
}

.tb-login__label {
  display: block;
  font-size: var(--tb-font-sm);
  color: var(--tb-text-2);
  margin: 0 0 var(--tb-gap-xs);
}

.tb-login__error {
  margin: 0 0 var(--tb-gap);
}

.tb-login__row {
  display: flex;
  align-items: center;
  margin-bottom: var(--tb-gap-lg);
}

.tb-login__submit {
  width: 100%;
}

.tb-login__init {
  margin-top: var(--tb-gap-lg);
}
</style>
