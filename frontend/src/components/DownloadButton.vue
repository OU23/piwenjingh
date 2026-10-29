<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { BizError, ForbiddenError, UnauthorizedError } from '@/api/request';
import { getDownloadUrl } from '@/api/tool';
import type { ID } from '@/api/types';
import { useUserStore } from '@/stores/user';

/**
 * 鉴权下载入口（工具列表与详情共用）
 *
 * 流程（契约 v1.19 §9.5）：先由后端**签发**短时效下载地址，再**导航下载** ——
 * - 文件在对象存储 → 签名直链（`direct=true`），浏览器**直连 OSS**，字节不过后端；
 * - 文件在本地 → 令牌地址（`direct=false`），仍由后端代理返回。
 *
 * 两条路径前端**同一个动作**（`<a>` 导航），不区分处理；**先鉴权后签发**由后端保证。
 * 进度 / 暂停 / 续传交给**浏览器原生下载器**（OSS 与后端均支持 `Range`），
 * 因此本组件不再依赖 File System Access API，也不再自管进度与续传状态。
 */
const props = defineProps<{
  toolId: ID;
  versionId?: ID;
  /** 文件名的建议值（`download` 属性仅同源地址生效；最终以服务端 `Content-Disposition` 为准） */
  suggestedName: string;
}>();

const emit = defineEmits<{ done: [] }>();

const router = useRouter();
const userStore = useUserStore();

const requesting = ref(false);

function handleError(error: unknown): void {
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
    // 含 2004 / 2005（资源已不存在）与 2006（对象存储暂不可用，E-02 隔离）
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

/** 导航下载：交给浏览器原生下载器，不把文件读入内存 */
function navigate(url: string): void {
  const link = document.createElement('a');
  link.href = url;
  link.download = props.suggestedName;
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();
}

async function handleDownload(): Promise<void> {
  if (requesting.value) {
    return; // 签发中：忽略重复点击，避免触发多份下载
  }
  requesting.value = true;
  try {
    const { url } = await getDownloadUrl(props.toolId, props.versionId);
    navigate(url);
    emit('done');
  } catch (error) {
    handleError(error);
  } finally {
    requesting.value = false;
  }
}
</script>

<template>
  <el-button link type="primary" :loading="requesting" @click="handleDownload">下载</el-button>
</template>
