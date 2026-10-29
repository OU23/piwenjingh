<script setup lang="ts">
import { computed } from 'vue';
import type { UploadProgress } from '@/api/chunkUpload';
import { formatDuration, formatFileSize, formatSpeed } from '@/utils/format';

/**
 * 上传进度面板：进度 / 速度 / 预计剩余 / 分片计数 + 暂停 / 继续 / 取消
 * 见 P3 计划 T3 / T5，验收 B1
 */
const props = withDefaults(
  defineProps<{
    progress: UploadProgress;
    paused?: boolean;
    /** 本次是从断点继续：check 返回的已传分片数 > 0 */
    resumedFrom?: number;
    merging?: boolean;
  }>(),
  { paused: false, resumedFrom: 0, merging: false },
);

const emit = defineEmits<{ pause: []; resume: []; cancel: [] }>();

const percent = computed(() => {
  const { uploadedBytes, totalBytes } = props.progress;
  if (totalBytes <= 0) {
    return 0;
  }
  return Math.min(100, Math.round((uploadedBytes / totalBytes) * 1000) / 10);
});

const sizeText = computed(
  () => `${formatFileSize(props.progress.uploadedBytes)} / ${formatFileSize(props.progress.totalBytes)}`,
);
</script>

<template>
  <div class="tb-progress">
    <el-progress :percentage="percent" :stroke-width="14" :text-inside="true" />

    <div class="tb-progress__meta">
      <span>已传 {{ sizeText }}</span>
      <span>分片 {{ props.progress.uploadedChunks }} / {{ props.progress.totalChunks }}</span>
      <span>速度 {{ formatSpeed(props.progress.speed) }}</span>
      <span>剩余约 {{ formatDuration(props.progress.remainingMs) }}</span>
    </div>

    <div v-if="props.resumedFrom > 0" class="tb-note is-info tb-progress__note">
      已从断点继续：服务端已有 {{ props.resumedFrom }} 个分片，本次只补传缺失部分，无需重传。
    </div>

    <div v-if="props.paused" class="tb-note tb-progress__note">已暂停，在途分片已中止；点「继续」后接着传（被中止的分片会重传）。</div>

    <div class="tb-progress__actions">
      <template v-if="props.merging">
        <span class="tb-muted">分片已传完，正在服务端合并…</span>
      </template>
      <template v-else-if="props.paused">
        <el-button type="primary" @click="emit('resume')">继续上传</el-button>
      </template>
      <template v-else>
        <el-button @click="emit('pause')">暂停</el-button>
      </template>
      <el-button type="danger" plain :disabled="props.merging" @click="emit('cancel')">取消上传</el-button>
    </div>
  </div>
</template>

<style scoped>
.tb-progress__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tb-gap-lg);
  margin-top: var(--tb-gap);
  font-size: var(--tb-font-xs);
  color: var(--tb-text-2);
}

.tb-progress__note {
  margin-top: var(--tb-gap);
}

.tb-progress__actions {
  display: flex;
  align-items: center;
  gap: var(--tb-gap-sm);
  margin-top: var(--tb-gap-lg);
}
</style>
