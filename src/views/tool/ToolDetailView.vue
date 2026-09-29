<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import DownloadButton from '@/components/DownloadButton.vue';
import EmptyState from '@/components/EmptyState.vue';
import ToolGrantDialog from '@/components/ToolGrantDialog.vue';
import ToolInfoPanel from '@/components/ToolInfoPanel.vue';
import ToolVersionTable from '@/components/ToolVersionTable.vue';
import { BizError, ForbiddenError } from '@/api/request';
import { getToolDetail, saveToolGrants, type ToolDetailVO } from '@/api/tool';
import { ws } from '@/api/ws';
import { BizCode, type ID } from '@/api/types';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import { formatFileSize } from '@/utils/format';

/**
 * 工具详情：基本信息 + 使用说明 + 版本列表（可下载最新版或指定版本）
 * 目标不可见 / 不存在 / 已删除时给出明确提示，不白屏（见 P2 验收 C3 / C4）
 */
const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const detail = ref<ToolDetailVO | null>(null);
const loading = ref(false);
const errorText = ref('');
const errorKind = ref<'not-found' | 'forbidden' | 'other'>('other');

const toolId = computed(() => String(route.params.id ?? ''));
const canDownload = computed(() => userStore.has(PERMISSION.TOOL_DOWNLOAD));
const canUpload = computed(() => userStore.has(PERMISSION.TOOL_UPLOAD));
/** 跨部门定向授权入口（契约 §9.8，v1.7） */
const canGrant = computed(() => userStore.has(PERMISSION.USER_GRANT));

const grantVisible = ref(false);
const grantSubmitting = ref(false);
const grantError = ref('');

/** 打开「授权」弹窗（与工具列表同款入口，操作同一份授权关系） */
function openGrant(): void {
  grantError.value = '';
  grantVisible.value = true;
}

async function handleSaveGrants(payload: { userIds: ID[] }): Promise<void> {
  if (!detail.value || grantSubmitting.value) {
    return;
  }

  const { id, name } = detail.value;
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

/**
 * 详情页「版本列表」开关：**暂时隐藏**（**不是删除** —— 组件、数据、接口都保留，便于后续恢复）。
 *
 * 隐藏原因（2026-09-29 产品决定）：版本相关交互可能还有较多调整，且可能涉及权限口径，
 * 待需求明确后再恢复；届时把这里改回 `true` 即可（组件 `ToolVersionTable` 与接口数据均已就绪）。
 */
const SHOW_VERSION_LIST = false;

const suggestedName = computed(() => {
  if (!detail.value) {
    return 'download';
  }
  const ext = detail.value.fileExt ? `.${detail.value.fileExt}` : '';
  return `${detail.value.name}-${detail.value.latestVersion}${ext}`;
});

async function fetchDetail(): Promise<void> {
  if (!toolId.value) {
    errorKind.value = 'not-found';
    errorText.value = '工具地址不正确';
    return;
  }

  loading.value = true;
  errorText.value = '';
  detail.value = null;

  try {
    detail.value = await getToolDetail(toolId.value);
  } catch (cause) {
    if (cause instanceof ForbiddenError) {
      errorKind.value = 'forbidden';
      errorText.value = '该工具不在你的可见范围内';
    } else if (
      cause instanceof BizError &&
      (cause.code === BizCode.TOOL_NOT_FOUND || cause.code === BizCode.NOT_FOUND)
    ) {
      errorKind.value = 'not-found';
      errorText.value = '该工具不存在或已被删除';
    } else {
      errorKind.value = 'other';
      errorText.value = cause instanceof Error ? cause.message : '详情加载失败，请稍后重试';
    }
  } finally {
    loading.value = false;
  }
}

function backToList(): void {
  void router.push({ name: 'tool-list' });
}

/** 追加版本：带上工具 ID 进入上传页（P3 接通） */
function goUploadNewVersion(): void {
  if (!detail.value) {
    return;
  }
  void router.push({ name: 'tool-upload', query: { toolId: detail.value.id } });
}

watch(toolId, () => {
  void fetchDetail();
});

/**
 * v1.17：收到 `GRANT_CHANGED` → 重新拉详情；若因此**失去可见权（403）** →
 * 提示并**返回工具列表**（验收口径）。**不登出**。
 */
async function handleGrantChanged(): Promise<void> {
  await fetchDetail();
  if (errorKind.value === 'forbidden') {
    ElMessage.warning('该工具已不可访问，已返回工具列表');
    backToList();
  }
}

onMounted(() => {
  void fetchDetail();
  ws.onGrantChanged(handleGrantChanged);
});

onUnmounted(() => {
  ws.onGrantChanged(null);
});
</script>

<template>
  <div class="tb-detail">
    <div>
      <el-button link type="primary" @click="backToList">← 返回工具列表</el-button>
    </div>

    <div v-if="loading" class="tb-card">
      <el-skeleton :rows="6" animated />
    </div>

    <EmptyState
      v-else-if="errorText"
      variant="error"
      :text="errorText"
      :hint="errorKind === 'other' ? '请检查网络或稍后重试' : '可返回列表查看其他工具'"
      action-text="返回工具列表"
      @action="backToList"
    />

    <template v-else-if="detail">
      <div class="tb-card tb-detail__head">
        <div class="tb-detail__title">
          <span class="tb-detail__name">{{ detail.name }}</span>
          <el-tag size="small">{{ detail.latestVersion }}</el-tag>
          <span class="tb-muted">{{ formatFileSize(detail.fileSize) }}</span>
        </div>

        <div class="tb-detail__ops">
          <DownloadButton
            v-if="canDownload"
            :tool-id="detail.id"
            :suggested-name="suggestedName"
            @done="fetchDetail"
          />
          <el-button v-if="canGrant && detail.scope !== 'PUBLIC'" @click="openGrant">授权</el-button>
          <el-button v-if="canUpload" @click="goUploadNewVersion">上传新版本</el-button>
        </div>
      </div>

      <div class="tb-card">
        <ToolInfoPanel :detail="detail" />
      </div>

      <div v-if="SHOW_VERSION_LIST" class="tb-card">
        <div class="tb-title">版本列表</div>
        <ToolVersionTable :tool-id="detail.id" :versions="detail.versions" @downloaded="fetchDetail" />
      </div>
    </template>

    <ToolGrantDialog
      v-model="grantVisible"
      :tool="detail"
      :submitting="grantSubmitting"
      :error-message="grantError"
      @save="handleSaveGrants"
    />
  </div>
</template>

<style scoped>
.tb-detail {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap);
}

.tb-detail__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tb-gap);
}

.tb-detail__title {
  display: flex;
  align-items: center;
  gap: var(--tb-gap-sm);
  min-width: 0;
}

.tb-detail__name {
  font-size: var(--tb-font-xl);
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tb-detail__ops {
  display: flex;
  align-items: center;
  gap: var(--tb-gap-sm);
  flex-shrink: 0;
}
</style>
