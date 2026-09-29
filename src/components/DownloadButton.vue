<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { BizError, ForbiddenError, UnauthorizedError, saveBlob } from '@/api/request';
import { fetchAsBlob, probeTotalSize, streamToTarget } from '@/api/download';
import { clearResume, fileSystemProvider, getResume, setResume, type SaveTargetProvider } from '@/api/saveTarget';
import { toolDownloadUrl, toolDownloadUrlWithToken, fetchDownloadToken } from '@/api/tool';
import type { ID } from '@/api/types';
import { useUserStore } from '@/stores/user';
import { formatFileSize } from '@/utils/format';

/**
 * 鉴权下载入口（工具列表与详情共用）
 * 走带令牌请求，不用静态直链；大文件流式落盘并支持中断续传（principles.md R4 / R5）。
 *
 * 非安全上下文（HTTP / IP 访问）下 File System Access API 不可用，按开关二选一：
 * - 方案 2-B 开启（VITE_NATIVE_DOWNLOAD=on，需后端提供 download-token 接口）：
 *   换短时效令牌 → 原生 `<a>` 下载，浏览器原生流式落盘、无内存上限；
 * - 方案 2-B 关闭（默认）：整包读入内存后保存到默认下载目录（受内存约束）。
 */
const NATIVE_DOWNLOAD_ENABLED = import.meta.env.VITE_NATIVE_DOWNLOAD === 'on';
const props = defineProps<{
  toolId: ID;
  versionId?: ID;
  /** 保存对话框的建议文件名 */
  suggestedName: string;
  /** 保存目标提供方；默认浏览器实现，测试可注入 */
  provider?: SaveTargetProvider;
}>();

const emit = defineEmits<{ done: [] }>();

const router = useRouter();
const userStore = useUserStore();

const status = ref<'idle' | 'picking' | 'running'>('idle');
const resumable = ref(false);
const loaded = ref(0);
const total = ref<number | null>(null);
const percent = ref<number | null>(null);
let controller: AbortController | null = null;

const resumeKey = computed(() => `${props.toolId}:${props.versionId ?? 'latest'}`);
const label = computed(() => (status.value === 'running' ? '取消' : resumable.value ? '继续下载' : '下载'));
const progressText = computed(() => {
  if (percent.value !== null && total.value !== null) {
    return `${percent.value}%（${formatFileSize(loaded.value)} / ${formatFileSize(total.value)}）`;
  }
  return `已下载 ${formatFileSize(loaded.value)}`;
});

/** 是否为"用户主动取消"（fetch / 读流被 abort 时抛 AbortError，其 message 是英文，如 "The user aborted a request."） */
function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { name?: unknown }).name === 'AbortError';
}

function handleError(error: unknown): void {
  // 用户点了"取消"：统一给可读文案，不暴露英文的 AbortError
  if (isAbortError(error)) {
    ElMessage.info('下载已取消');
    return;
  }
  if (error instanceof UnauthorizedError) {
    userStore.clear();
    ElMessage.error(error.message);
    void router.push({ name: 'login' });
    return;
  }
  if (error instanceof ForbiddenError) {
    ElMessage.error(error.message || '无下载权限');
    return;
  }
  if (error instanceof BizError) {
    ElMessage.error(error.message);
    return;
  }
  // fetch 网络层失败只给英文 TypeError，统一转成可读提示
  if (error instanceof TypeError) {
    ElMessage.error('无法连接服务器，请确认后端服务已启动');
    return;
  }
  ElMessage.error(error instanceof Error ? error.message : '下载失败，请稍后重试');
}

/**
 * 降级路径：非安全上下文（HTTP / IP 访问）时 File System Access API 不可用，
 * 改用带令牌 fetch 整包读入后由浏览器落盘到**默认下载目录**（无“另存为”对话框）。
 * 此路径仍走带令牌接口，故 HTTP / IP 下也能鉴权下载；**不限制文件大小**。
 */
async function runFallback(url: string, signal: AbortSignal): Promise<void> {
  const token = userStore.token;
  // 预检：不可下载时尽早失败，并把后端原因抛给调用方（不再按大小给告警——任意大小都可下载）
  await probeTotalSize({ url, token, signal });

  const { blob, fileName } = await fetchAsBlob({
    url,
    token,
    signal,
    onProgress: (progress) => {
      loaded.value = progress.loaded;
      total.value = progress.total;
      percent.value = progress.percent;
    },
  });
  saveBlob(blob, fileName ?? props.suggestedName);
  ElMessage.success('下载完成（已保存到浏览器默认下载目录）');
}

/**
 * 方案 2-B：非安全上下文下的原生下载。
 * 先向后端换取短时效下载令牌，再用浏览器原生 `<a>` 触发下载——浏览器原生流式落盘，
 * 不受 FSA 安全上下文限制，也不受整包读入的内存约束，适合 HTTP / IP 下的大文件。
 * 返回 false 表示令牌获取失败（如后端尚未提供该接口），调用方回退整包读入降级路径。
 */
async function runNativeDownload(signal: AbortSignal): Promise<boolean> {
  let token: string;
  try {
    const issued = await fetchDownloadToken(props.toolId, props.versionId);
    token = issued.token;
  } catch {
    // 用户主动取消：不再回退，直接结束
    return signal.aborted;
  }

  const link = document.createElement('a');
  link.href = toolDownloadUrlWithToken(props.toolId, token, props.versionId);
  link.download = props.suggestedName; // 以服务端 Content-Disposition 为准，此处仅兜底
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();
  ElMessage.success('已开始下载（浏览器原生下载，进度见浏览器下载列表）');
  return true;
}

async function handleDownload(): Promise<void> {
  if (status.value === 'picking') {
    return; // 选择保存位置中：忽略重复点击（避免触发多份下载）
  }
  if (status.value === 'running') {
    controller?.abort();
    return;
  }

  const url = toolDownloadUrl(props.toolId, props.versionId);
  const signalController = new AbortController();
  const provider = props.provider ?? fileSystemProvider;
  const existing = getResume(resumeKey.value);
  controller = signalController;

  try {
    if (!existing && !provider.supported) {
      status.value = 'running';
      // 非安全上下文：方案 2-B 就绪时优先走原生下载（浏览器原生落盘、无内存上限），
      // 令牌获取失败（后端未就绪等）则回退整包读入降级路径。
      if (NATIVE_DOWNLOAD_ENABLED && (await runNativeDownload(signalController.signal))) {
        return;
      }
      await runFallback(url, signalController.signal);
      return;
    }

    let target = existing?.target ?? null;
    if (!target) {
      status.value = 'picking';
      target = await provider.pick(props.suggestedName);
      if (!target) {
        return; // 用户取消了保存位置选择
      }
    }

    status.value = 'running';
    const outcome = await streamToTarget({
      url,
      token: userStore.token,
      target,
      offset: existing?.written ?? 0,
      signal: signalController.signal,
      onProgress: (progress) => {
        loaded.value = progress.loaded;
        total.value = progress.total;
        percent.value = progress.percent;
      },
    });

    if (outcome.done) {
      clearResume(resumeKey.value);
      resumable.value = false;
      loaded.value = 0;
      total.value = null;
      percent.value = null;
      ElMessage.success('下载完成');
      emit('done');
    } else {
      setResume(resumeKey.value, { target, written: outcome.written, total: outcome.total });
      resumable.value = true;
      ElMessage.warning(`下载已中断，可点击"继续下载"续传（已保存 ${formatFileSize(outcome.written)}）`);
    }
  } catch (error) {
    resumable.value = false;
    handleError(error);
  } finally {
    status.value = 'idle';
    controller = null;
  }
}
</script>

<template>
  <span class="tb-dl">
    <el-button link type="primary" :loading="status === 'picking'" @click="handleDownload">
      {{ label }}
    </el-button>
    <span v-if="status === 'running'" class="tb-dl__progress">{{ progressText }}</span>
  </span>
</template>

<style scoped>
.tb-dl {
  display: inline-flex;
  align-items: center;
  gap: var(--tb-gap-sm);
  white-space: nowrap;
}

.tb-dl__progress {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}
</style>
