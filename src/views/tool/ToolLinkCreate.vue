<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import ToolMetaForm from '@/components/ToolMetaForm.vue';
import { createLinkTool } from '@/api/tool';
import { listTags, type TagVO } from '@/api/tag';
import { useUserStore } from '@/stores/user';
import { validateToolMeta, type ToolMetaValues } from '@/utils/toolMetaValidation';
import { useAssignDeptOptions } from './deptOptions';

/**
 * 创建链接工具（M-25 / 需求 6）
 *
 * 与文件工具的区别：**没有文件、没有版本**（`POST /api/tools/link`），其余元数据一致；
 * 链接地址只允许 `http/https`，前端先校验、后端必再校验一次。
 */
const router = useRouter();
const userStore = useUserStore();
const { canAssign, deptOptions } = useAssignDeptOptions();

const tags = ref<TagVO[]>([]);
const tagsError = ref('');
const submitting = ref(false);
const submitError = ref('');

/** 归属部门默认自己部门（仅 tool:assign 可见/可改） */
const initial = ref<Partial<ToolMetaValues>>({ deptId: userStore.user?.deptId ?? '' });

async function fetchTags(): Promise<void> {
  tagsError.value = '';
  try {
    tags.value = await listTags();
  } catch (cause) {
    tags.value = [];
    tagsError.value = cause instanceof Error ? cause.message : '标签字典加载失败';
  }
}

async function handleSubmit(values: ToolMetaValues): Promise<void> {
  if (submitting.value) {
    return;
  }
  const message = validateToolMeta('create', values, { linkMode: true });
  if (message) {
    submitError.value = message;
    return;
  }

  submitting.value = true;
  submitError.value = '';
  try {
    const created = await createLinkTool({
      name: values.name.trim(),
      linkUrl: values.linkUrl.trim(),
      intro: values.intro.trim(),
      readme: values.readme.trim() || undefined,
      platform: values.platform.trim() || undefined,
      dependency: values.dependency.trim() || undefined,
      tagIds: values.tagIds.length > 0 ? values.tagIds : undefined,
      scope: values.scope,
      deptId: values.deptId || undefined,
    });
    ElMessage.success('链接工具已创建');
    void router.push({ name: 'tool-detail', params: { id: created.toolId } });
  } catch (cause) {
    submitError.value = cause instanceof Error ? cause.message : '创建失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

function goBack(): void {
  void router.push({ name: 'tool-list' });
}

onMounted(() => {
  void fetchTags();
});
</script>

<template>
  <div class="tb-card">
    <div class="tb-note is-info tb-link__hint">
      链接工具只保存网址：没有文件、没有版本，团队成员打开即可使用（仍按可见范围控制谁能看到）。
    </div>

    <ToolMetaForm
      mode="create"
      link-mode
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
</template>

<style scoped>
.tb-link__hint {
  margin-bottom: var(--tb-gap-lg);
}
</style>
