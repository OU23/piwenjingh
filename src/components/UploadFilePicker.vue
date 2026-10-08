<script setup lang="ts">
import { computed, ref } from 'vue';
import { validatePickedFile } from '@/utils/fileValidation';
import { formatFileSize } from '@/utils/format';

/**
 * 选择文件（拖拽或点击）：**单个文件**，不限扩展名
 * 超上限 / 空文件在此直接拦截，不进入上传（见 P3 计划 T2、验收 A6）
 */
const props = withDefaults(
  defineProps<{
    maxSize?: number;
    disabled?: boolean;
  }>(),
  { maxSize: 0, disabled: false },
);

const emit = defineEmits<{ picked: [file: File]; error: [message: string] }>();

const file = ref<File | null>(null);
const dragging = ref(false);
const inputRef = ref<HTMLInputElement | null>(null);

const fileSizeText = computed(() => (file.value ? formatFileSize(file.value.size) : ''));

function accept(next: File | undefined): void {
  if (!next) {
    return;
  }

  const invalid = validatePickedFile(next, props.maxSize);
  if (invalid) {
    file.value = null;
    emit('error', invalid);
    return;
  }

  file.value = next;
  emit('picked', next);
}

function handleChange(event: Event): void {
  const target = event.target as HTMLInputElement;
  accept(target.files?.[0]);
  // 允许重复选择同一文件（否则 change 不触发）
  target.value = '';
}

function handleDrop(event: DragEvent): void {
  dragging.value = false;
  if (props.disabled) {
    return;
  }

  const dropped = event.dataTransfer?.files;
  if (dropped && dropped.length > 1) {
    emit('error', '一次只能上传一个文件；多级目录请先打包为压缩包再上传');
    return;
  }
  accept(dropped?.[0]);
}

function openPicker(): void {
  if (!props.disabled) {
    inputRef.value?.click();
  }
}
</script>

<template>
  <div class="tb-picker">
    <div
      class="tb-picker__zone"
      :class="{ 'is-dragging': dragging, 'is-disabled': props.disabled }"
      @click="openPicker"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="handleDrop"
    >
      <div class="tb-picker__title">点击选择文件，或把文件拖到这里</div>
      <div class="tb-muted">支持任意格式：exe / py / jar / 脚本 / zip / 7z / rar / gz…</div>
      <div class="tb-picker__limit">
        {{ props.maxSize > 0 ? `单个文件上限 ${formatFileSize(props.maxSize)}` : '正在读取单文件上限…' }}
      </div>
      <input ref="inputRef" class="tb-picker__input" type="file" @change="handleChange" />
    </div>

    <div v-if="file" class="tb-picker__picked">
      <span class="tb-picker__picked-name">{{ file.name }}</span>
      <span class="tb-muted">{{ fileSizeText }}</span>
      <el-button link type="primary" :disabled="props.disabled" @click="openPicker">重新选择</el-button>
    </div>

    <div class="tb-note is-info tb-picker__tip">
      多级目录的工程（如 Java / 前端项目）请先在本机打包成压缩包，再作为<strong>单个文件</strong>上传；压缩包按整体文件存储与下载。
    </div>
  </div>
</template>

<style scoped>
.tb-picker__zone {
  border: 1px dashed var(--tb-border);
  border-radius: var(--tb-radius);
  padding: var(--tb-gap-xl);
  text-align: center;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}

.tb-picker__zone:hover,
.tb-picker__zone.is-dragging {
  border-color: var(--tb-accent);
  background: var(--tb-accent-soft);
}

.tb-picker__zone.is-disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.tb-picker__title {
  font-size: var(--tb-font-sm);
  margin-bottom: var(--tb-gap-xs);
}

.tb-picker__limit {
  margin-top: var(--tb-gap-sm);
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}

.tb-picker__input {
  display: none;
}

.tb-picker__picked {
  display: flex;
  align-items: center;
  gap: var(--tb-gap);
  margin-top: var(--tb-gap);
  font-size: var(--tb-font-sm);
}

.tb-picker__picked-name {
  word-break: break-all;
}

.tb-picker__tip {
  margin-top: var(--tb-gap);
}
</style>
