<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageContainer from '@/components/PageContainer.vue';
import { getSettings, saveSettings, type SettingVO } from '@/api/setting';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import { formatFileSize } from '@/utils/format';
import {
  FILE_SIZE_LIMIT_GB,
  buildSettingSaveDTO,
  hasSettingChanges,
  settingConfirmMessage,
  toSettingFormValues,
  validateSettingForm,
  type SettingFormValues,
} from '@/utils/settingValidation';

/**
 * 全局设置（契约 §十，计划 T5）
 * - 存储根路径为**手动输入的绝对路径**：不提供目录 / 文件选择控件（服务端可能为 Linux，无本地盘可浏览）
 * - 保存前二次确认，且**必须提示"已上传文件不会自动迁移"**
 * - **只提交变更项**；路径不可用时就地展示后端原因，不误报成功
 */
const userStore = useUserStore();

const current = ref<SettingVO | null>(null);
const form = reactive<SettingFormValues>({ storageRoot: '', maxFileSizeGb: 0 });
const loading = ref(false);
const error = ref<string | null>(null);
const saving = ref(false);
const validationError = ref('');
const saveError = ref('');

const canSave = computed(() => userStore.has(PERMISSION.SYS_SETTING));
const currentLimitText = computed(() =>
  current.value ? `当前生效：${formatFileSize(current.value.maxFileSize)}` : '正在读取当前设置…',
);

async function fetchSettings(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const settings = await getSettings();
    current.value = settings;
    Object.assign(form, toSettingFormValues(settings));
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '设置加载失败，请稍后重试';
    current.value = null;
  } finally {
    loading.value = false;
  }
}

async function handleSave(): Promise<void> {
  if (!current.value || saving.value) {
    return;
  }

  const invalid = validateSettingForm(form);
  validationError.value = invalid;
  if (invalid) {
    return;
  }

  const dto = buildSettingSaveDTO(current.value, form);
  if (!hasSettingChanges(dto)) {
    ElMessage.info('没有需要保存的修改');
    return;
  }

  try {
    await ElMessageBox.confirm(settingConfirmMessage(dto.storageRoot !== undefined), '保存全局设置', {
      type: 'warning',
      confirmButtonText: '确定保存',
      cancelButtonText: '取消',
    });
  } catch {
    return; // 取消二次确认 → 不发起请求
  }

  saving.value = true;
  saveError.value = '';

  try {
    await saveSettings(dto);
    ElMessage.success('设置已保存');
    await fetchSettings();
  } catch (cause) {
    // 路径不存在 / 不可写等：展示后端原因，不误报成功，保留已填内容
    saveError.value = cause instanceof Error ? cause.message : '保存失败，请稍后重试';
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  void fetchSettings();
});
</script>

<template>
  <div class="tb-page">
    <PageContainer :loading="loading" :error="error" :empty="false" @retry="fetchSettings">
      <div class="tb-card tb-setting">
        <div class="tb-setting__item">
          <label class="tb-setting__label" for="setting-root">存储根路径</label>
          <el-input
            id="setting-root"
            v-model="form.storageRoot"
            :disabled="!canSave"
            placeholder="例如 Linux：/data/toolbox/files ；Windows：D:\toolbox\files"
          />
          <span v-if="!canSave" class="tb-setting__hint">
            当前账号无权查看存储路径（需「全局设置」权限）。
          </span>
          <span class="tb-muted">
            工具文件保存在服务器的这个目录下（由管理员手动填写绝对路径）；不提供目录选择器，因为服务端通常是 Linux。
          </span>
        </div>

        <div class="tb-setting__item">
          <label class="tb-setting__label" for="setting-limit">单文件大小上限（GB）</label>
          <el-input-number
            id="setting-limit"
            v-model="form.maxFileSizeGb"
            :min="FILE_SIZE_LIMIT_GB.min"
            :max="FILE_SIZE_LIMIT_GB.max"
            :step="1"
            :precision="1"
          />
          <span class="tb-muted">默认 10 GB；{{ currentLimitText }}。修改后仅对之后的上传生效。</span>
        </div>

        <div class="tb-note tb-setting__warning">
          ⚠️ 修改存储根路径后，<strong>之前已上传的文件不会自动迁移</strong>：需要人工把旧目录下的文件拷贝到新目录，否则这些工具将无法下载。
        </div>

        <div v-if="validationError" class="tb-note is-danger">{{ validationError }}</div>
        <div v-if="saveError" class="tb-note is-danger">{{ saveError }}</div>

        <div class="tb-setting__actions">
          <el-button v-if="canSave" type="primary" :loading="saving" @click="handleSave">保存设置</el-button>
          <span v-else class="tb-muted">你没有修改全局设置的权限，当前为只读查看。</span>
        </div>
      </div>
    </PageContainer>
  </div>
</template>

<style scoped>
.tb-page {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap);
}

.tb-setting {
  max-width: 720px;
}

.tb-setting__item {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap-xs);
  margin-bottom: var(--tb-gap-lg);
}

.tb-setting__label {
  font-size: var(--tb-font-sm);
  color: var(--tb-text);
}

.tb-setting__hint {
  font-size: var(--tb-font-xs);
  color: var(--tb-danger);
}

.tb-setting__warning {
  margin-bottom: var(--tb-gap);
}

.tb-setting__actions {
  margin-top: var(--tb-gap-lg);
}
</style>
