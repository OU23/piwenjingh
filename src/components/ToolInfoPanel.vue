<script setup lang="ts">
import { computed } from 'vue';
import type { ToolDetailVO } from '@/api/tool';
import { SCOPE_LABEL, type ScopeValue } from '@/constants/enums';
import { formatDateTime, formatFileSize } from '@/utils/format';

/**
 * 工具详情的基本信息区（元数据 / 简介 / 使用说明）
 * 使用说明为纯文本，必须保留换行（契约 §9.3）
 */
const props = defineProps<{ detail: ToolDetailVO }>();

function fallback(value?: string | null): string {
  return value && value.trim().length > 0 ? value : '—';
}

const items = computed<{ label: string; value: string }[]>(() => [
  { label: '版本', value: props.detail.latestVersion || '—' },
  { label: '格式', value: props.detail.fileExt ? `.${props.detail.fileExt}` : '—' },
  { label: '大小', value: formatFileSize(props.detail.fileSize) },
  { label: '运行平台', value: fallback(props.detail.platform) },
  { label: '标签', value: props.detail.tags.map((tag) => tag.name).join('、') || '—' },
  { label: '可见范围', value: SCOPE_LABEL[props.detail.scope as ScopeValue] ?? props.detail.scope },
  { label: '上传人', value: props.detail.uploaderName || '—' },
  { label: '归属部门', value: props.detail.deptName || '—' },
  { label: '上传时间', value: formatDateTime(props.detail.createTime) },
  { label: '最近更新', value: formatDateTime(props.detail.updateTime) },
  // M-23：不再展示「下载次数」（该字段实为签发次数，非下载成功次数，见方案 §2.7）
  { label: '依赖说明', value: fallback(props.detail.dependency) },
]);
</script>

<template>
  <div class="tb-info">
    <div class="tb-info__grid">
      <div v-for="item in items" :key="item.label" class="tb-info__cell">
        <span class="tb-info__label">{{ item.label }}</span>
        <span class="tb-info__value">{{ item.value }}</span>
      </div>
    </div>

    <div class="tb-info__block">
      <div class="tb-info__label">简介</div>
      <p class="tb-info__text">{{ fallback(props.detail.intro) }}</p>
    </div>

    <div class="tb-info__block">
      <div class="tb-info__label">使用说明</div>
      <pre class="tb-info__readme">{{ fallback(props.detail.readme) }}</pre>
    </div>
  </div>
</template>

<style scoped>
.tb-info__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--tb-gap) var(--tb-gap-lg);
  margin-bottom: var(--tb-gap-lg);
}

.tb-info__cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.tb-info__label {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}

.tb-info__value {
  font-size: var(--tb-font-sm);
  color: var(--tb-text);
  word-break: break-all;
}

.tb-info__block + .tb-info__block {
  margin-top: var(--tb-gap-lg);
}

.tb-info__text,
.tb-info__readme {
  margin: var(--tb-gap-xs) 0 0;
  padding: var(--tb-gap);
  background: var(--tb-panel-2);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius-sm);
  font-size: var(--tb-font-sm);
  line-height: 1.8;
  color: var(--tb-text-2);
  /* 使用说明为纯文本：保留原始换行与空格，避免排版失真 */
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}

.tb-info__readme {
  max-height: 360px;
  overflow: auto;
}
</style>
