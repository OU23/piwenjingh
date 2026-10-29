<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import PageContainer from '@/components/PageContainer.vue';
import ToolMetaForm from '@/components/ToolMetaForm.vue';
import { getToolDetail, updateTool, updateToolDept, type ToolDetailVO } from '@/api/tool';
import { listTags, type TagVO } from '@/api/tag';
import { validateToolMeta, type ToolMetaValues } from '@/utils/toolMetaValidation';
import { useToolEditPermission } from '@/utils/toolPermission';
import { useAssignDeptOptions } from './deptOptions';

/**
 * 工具编辑页（M-25 / P20 T7）
 *
 * 字段边界（契约 v1.23 + 老大 2026-10-20 决策）：
 * - **名称、版本号、文件/格式均只读**（本期不做改名，版本号属版本记录）；
 * - 可改：简介 / 使用说明 / 依赖 / 平台 / 标签 / 可见范围 / 归属部门（需 `tool:assign`）/ 链接地址（仅 LINK）。
 *
 * 保存顺序（方案 §2.5）：
 * 1. `PUT /api/tools/{id}` 保存内容；
 * 2. 内容成功后、且部门有变化 → `PUT /api/tools/{id}/dept`；
 * 3. 第 2 步失败**不回滚内容**，明确提示"基本信息已保存，归属部门调整失败"。
 */
const route = useRoute();
const router = useRouter();
const { canAssign, deptOptions } = useAssignDeptOptions();

const id = computed(() => String(route.params.id ?? ''));
const detail = ref<ToolDetailVO | null>(null);
const tags = ref<TagVO[]>([]);
const tagsError = ref('');
const loading = ref(true);
const error = ref<string | null>(null);
const submitError = ref('');
const submitting = ref(false);

const isLink = computed(() => detail.value?.toolType === 'LINK');

/** 与后端 `requireEditable` 同口径（统一收敛在 `useToolEditPermission`） */
const canEditTool = useToolEditPermission();
const canEdit = computed(() => canEditTool(detail.value));

/** 表单初值（名称只读回显用 `initial.name`） */
const initial = computed<Partial<ToolMetaValues> | null>(() => {
  const d = detail.value;
  if (!d) {
    return null;
  }
  return {
    name: d.name,
    intro: d.intro ?? '',
    readme: d.readme ?? '',
    fileExt: d.fileExt ?? '',
    platform: d.platform ?? '',
    dependency: d.dependency ?? '',
    tagIds: d.tags.map((tag) => tag.id),
    scope: d.scope,
    deptId: d.deptId ?? '',
    linkUrl: d.linkUrl ?? '',
  };
});

async function fetchDetail(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    detail.value = await getToolDetail(id.value);
  } catch (cause) {
    detail.value = null;
    error.value = cause instanceof Error ? cause.message : '工具加载失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}

async function fetchTags(): Promise<void> {
  tagsError.value = '';
  try {
    tags.value = await listTags();
  } catch (cause) {
    tags.value = [];
    tagsError.value = cause instanceof Error ? cause.message : '标签字典加载失败';
  }
}

function goBack(): void {
  void router.push({ name: 'tool-detail', params: { id: id.value } });
}

async function handleSubmit(values: ToolMetaValues): Promise<void> {
  const current = detail.value;
  if (!current || submitting.value) {
    return;
  }

  const message = validateToolMeta('edit', values, { linkMode: isLink.value });
  if (message) {
    submitError.value = message;
    return;
  }

  submitting.value = true;
  submitError.value = '';

  try {
    await updateTool(id.value, {
      // 本期不改名：恒回传原名（后端保持现状，不会产生 1005）
      name: current.name,
      intro: values.intro.trim(),
      readme: values.readme.trim() || undefined,
      platform: values.platform.trim() || undefined,
      dependency: values.dependency.trim() || undefined,
      tagIds: values.tagIds,
      scope: values.scope,
      // FILE 传原值（兼容未去掉 @NotBlank 的旧后端）；LINK 两项本就不存在，不传
      ...(isLink.value
        ? { linkUrl: values.linkUrl.trim() }
        : { version: current.latestVersion, fileExt: current.fileExt }),
    });
  } catch (cause) {
    submitError.value = cause instanceof Error ? cause.message : '保存失败，请稍后重试';
    submitting.value = false;
    return;
  }

  // 内容已保存：部门调整放后面，失败不回滚内容，但必须说清楚
  const nextDept = values.deptId;
  if (canAssign.value && nextDept && nextDept !== current.deptId) {
    try {
      await updateToolDept(id.value, nextDept);
    } catch (cause) {
      const reason = cause instanceof Error ? cause.message : '请稍后重试';
      submitError.value = `基本信息已保存，归属部门调整失败：${reason}`;
      await fetchDetail();
      submitting.value = false;
      return;
    }
  }

  submitting.value = false;
  ElMessage.success('保存成功');
  goBack();
}

onMounted(() => {
  void fetchDetail();
  void fetchTags();
});
</script>

<template>
  <div class="tb-edit">
    <PageContainer :loading="loading" :error="error" @retry="fetchDetail">
      <div v-if="detail" class="tb-card">
        <div class="tb-edit__head">
          <span class="tb-edit__title">编辑工具「{{ detail.name }}」</span>
          <el-tag size="small" :type="isLink ? 'success' : 'info'">{{ isLink ? '链接' : '工具' }}</el-tag>
        </div>

        <div class="tb-note is-info tb-edit__hint">
          名称{{ isLink ? '' : '、版本号与文件格式' }}不可在此修改；如需更新文件内容，请到详情页「上传新版本」。
        </div>

        <div v-if="!canEdit" class="tb-note is-danger">你没有该工具的编辑权限（仅上传人本人或管理员可编辑）。</div>

        <ToolMetaForm
          v-else
          mode="edit"
          :link-mode="isLink"
          :initial="initial"
          :tags="tags"
          :tags-hint="tagsError"
          :dept-options="deptOptions"
          :can-assign="canAssign"
          :submitting="submitting"
          :error-message="submitError"
          @submit="handleSubmit"
          @cancel="goBack"
        />
      </div>
    </PageContainer>
  </div>
</template>

<style scoped>
.tb-edit {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap);
}

.tb-edit__head {
  display: flex;
  align-items: center;
  gap: var(--tb-gap-sm);
  margin-bottom: var(--tb-gap);
}

.tb-edit__title {
  font-size: var(--tb-font-lg);
  font-weight: 600;
}

.tb-edit__hint {
  margin-bottom: var(--tb-gap-lg);
}
</style>
