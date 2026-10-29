<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { saveBlob } from '@/api/request';
import type { ImportResultVO } from '@/api/types';
import { base64ToBlob } from '@/utils/base64';
import { IMPORT_FILE_MAX_SIZE, validateImportFile } from '@/utils/fileValidation';
import { formatFileSize } from '@/utils/format';

/**
 * Excel 导入对话框（部门 / 用户共用；计划 P8 T2）
 * - 模板与「错误明细 Excel」**都由后端生成**，前端**不解析、不生成 Excel**（零新增依赖）
 * - 提交前**前端先预校验**（仅 `.xlsx`、≤ 10 MB）→ 不满足**就地拦截、不发请求**
 * - **「跳过」≠「失败」**：跳过（已存在）用中性色、失败用错误色，**两者不得混用**（契约 §6.2）
 * - 请求失败 → **就地提示且不关闭弹窗**（可重试）；提交中**禁用**按钮（防重复提交）
 * - 关闭弹窗时若**有成功行** → 触发 `imported`，由父页刷新列表
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    /** 弹窗标题，如「Excel 导入 · 部门」 */
    title: string;
    /** 模板文件名提示，如「部门导入模板.xlsx」 */
    templateName?: string;
    /** 下载模板（父页注入；内部已走鉴权下载通道并落盘） */
    downloadTemplate: () => Promise<void>;
    /** 提交导入（父页注入对应域的 import 接口） */
    submit: (file: File) => Promise<ImportResultVO>;
  }>(),
  { templateName: '' },
);

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  /** 关闭且**有成功行**时触发：父页据此刷新列表 */
  imported: [];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

const fileInput = ref<HTMLInputElement | null>(null);
const picked = ref<File | null>(null);
const pickError = ref('');
const submitting = ref(false);
const requestError = ref('');
const downloadingTemplate = ref(false);
const result = ref<ImportResultVO | null>(null);

const sizeHint = formatFileSize(IMPORT_FILE_MAX_SIZE);
/** 只有成功入库才需要刷新父页列表（全跳过 / 全失败时数据未变） */
const hasImported = computed(() => (result.value?.successCount ?? 0) > 0);

function reset(): void {
  picked.value = null;
  pickError.value = '';
  requestError.value = '';
  result.value = null;
  submitting.value = false;
  if (fileInput.value) {
    fileInput.value.value = '';
  }
}

function handleFileChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  // 清空 input 的值：否则"取消后再选同一个文件"不会触发 change
  input.value = '';

  if (!file) {
    return;
  }

  const invalid = validateImportFile(file);
  pickError.value = invalid;
  // 换文件即视为新一轮导入：清掉上一次的结果与错误
  result.value = null;
  requestError.value = '';
  picked.value = invalid ? null : file;
}

async function handleDownloadTemplate(): Promise<void> {
  if (downloadingTemplate.value) {
    return;
  }

  downloadingTemplate.value = true;
  try {
    await props.downloadTemplate();
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '模板下载失败，请稍后重试');
  } finally {
    downloadingTemplate.value = false;
  }
}

async function handleImport(): Promise<void> {
  const file = picked.value;
  if (!file || submitting.value) {
    return;
  }

  submitting.value = true;
  requestError.value = '';
  try {
    result.value = await props.submit(file);
  } catch (cause) {
    // 失败：就地提示、**不关闭弹窗**，便于重试
    requestError.value = cause instanceof Error ? cause.message : '导入失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

function handleDownloadErrors(): void {
  const current = result.value;
  if (!current?.errorFileBase64) {
    return;
  }

  const blob = base64ToBlob(current.errorFileBase64);
  if (!blob) {
    ElMessage.error('错误明细文件解析失败，请重试');
    return;
  }
  saveBlob(blob, current.errorFileName || '导入错误明细.xlsx');
}

function handleClosed(): void {
  if (hasImported.value) {
    emit('imported');
  }
}

watch(
  () => props.modelValue,
  (open) => {
    // 每次打开都是干净状态，不残留上一轮结果（验收 C10）
    if (open) {
      reset();
    }
  },
);
</script>

<template>
  <el-dialog v-model="visible" :title="props.title" width="560px" :close-on-click-modal="false" @close="handleClosed">
    <div class="tb-import">
      <div class="tb-import__head">
        <el-button :loading="downloadingTemplate" @click="handleDownloadTemplate">下载模板</el-button>
        <span class="tb-muted">
          请按模板格式填写后再导入{{ props.templateName ? `（模板：${props.templateName}）` : '' }}
        </span>
      </div>

      <div class="tb-import__picker">
        <el-button @click="fileInput?.click()">选择文件</el-button>
        <span v-if="picked" class="tb-import__picked">{{ picked.name }}（{{ formatFileSize(picked.size) }}）</span>
        <span v-else class="tb-muted">未选择文件（仅 .xlsx，且不超过 {{ sizeHint }}）</span>
        <input ref="fileInput" class="tb-import__input" type="file" accept=".xlsx" @change="handleFileChange" />
      </div>

      <div v-if="pickError" class="tb-note is-danger">{{ pickError }}</div>
      <div v-if="requestError" class="tb-note is-danger">{{ requestError }}</div>

      <div v-if="result" class="tb-import__result">
        <div class="tb-import__counts">
          <span class="tb-import__total">共 {{ result.total }} 行</span>
          <span class="tb-import__ok">成功 {{ result.successCount }}</span>
          <span class="tb-import__skip">跳过 {{ result.skipCount }}</span>
          <span class="tb-import__fail">失败 {{ result.failCount }}</span>
        </div>

        <div v-if="result.createdDeptNames.length" class="tb-note tb-import__created">
          本次自动新建了 <strong>{{ result.createdDeptNames.length }}</strong> 个部门：{{
            result.createdDeptNames.join('、')
          }}
        </div>

        <div v-if="result.errors.length" class="tb-import__errors">
          <div class="tb-import__errors-title">失败明细（前 {{ result.errors.length }} 条）</div>
          <ul class="tb-import__errors-list">
            <li v-for="item in result.errors" :key="`${item.rowNum}-${item.message}`">
              第 {{ item.rowNum }} 行：{{ item.message }}
            </li>
          </ul>
        </div>

        <div v-if="result.failCount > 0" class="tb-import__download">
          <el-button type="primary" plain @click="handleDownloadErrors">下载错误明细 Excel</el-button>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :disabled="!picked || submitting" :loading="submitting" @click="handleImport">
        开始导入
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tb-import {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap);
}

.tb-import__head {
  display: flex;
  align-items: center;
  gap: var(--tb-gap-sm);
  flex-wrap: wrap;
  font-size: var(--tb-font-xs);
}

.tb-import__picker {
  display: flex;
  align-items: center;
  gap: var(--tb-gap-sm);
  flex-wrap: wrap;
  padding: var(--tb-gap);
  border: 1px dashed var(--tb-border);
  border-radius: var(--tb-radius-sm);
  background: var(--tb-panel-3);
  font-size: var(--tb-font-xs);
}

.tb-import__input {
  display: none;
}

.tb-import__picked {
  color: var(--tb-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tb-import__result {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap-sm);
}

.tb-import__counts {
  display: flex;
  gap: var(--tb-gap-lg);
  font-size: var(--tb-font-sm);
}

.tb-import__total {
  color: var(--tb-text);
}

.tb-import__ok {
  color: var(--tb-success);
}

/* 「跳过」用中性色，「失败」用错误色 —— 两者不得混用（契约 §6.2） */
.tb-import__skip {
  color: var(--tb-text-3);
}

.tb-import__fail {
  color: var(--tb-danger);
}

.tb-import__created {
  color: var(--tb-text);
}

.tb-import__created strong {
  color: var(--tb-warning);
}

.tb-import__errors {
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius-sm);
  background: var(--tb-panel-3);
  padding: var(--tb-gap-sm) var(--tb-gap);
}

.tb-import__errors-title {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
  margin-bottom: var(--tb-gap-xs);
}

.tb-import__errors-list {
  max-height: 160px;
  overflow-y: auto;
  margin: 0;
  padding-left: 18px;
  font-size: var(--tb-font-xs);
  color: var(--tb-text-2);
  line-height: 1.8;
}

.tb-import__download {
  display: flex;
  justify-content: flex-end;
}
</style>
