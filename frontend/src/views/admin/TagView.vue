<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageContainer from '@/components/PageContainer.vue';
import { createTag, deleteTag, listTags, updateTag, type TagVO } from '@/api/tag';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import { validateTagName } from '@/utils/deptValidation';
import { formatDateTime } from '@/utils/format';

/**
 * 标签管理（契约 §七，计划 T4）
 * 删除标签**只解除与工具的关联，不删除工具** —— 提示必须写清影响面（关联工具数）。
 */
const userStore = useUserStore();

const list = ref<TagVO[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);

const newName = ref('');
const createError = ref('');
const submitting = ref(false);

const renameVisible = ref(false);
const renaming = ref<TagVO | null>(null);
const renameValue = ref('');
const renameError = ref('');

const canManage = computed(() => userStore.has(PERMISSION.TAG_MANAGE));
const isEmpty = computed(() => !loading.value && !error.value && list.value.length === 0);

async function fetchList(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    list.value = await listTags();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '标签列表加载失败，请稍后重试';
    list.value = [];
  } finally {
    loading.value = false;
  }
}

async function handleCreate(): Promise<void> {
  const invalid = validateTagName(newName.value);
  createError.value = invalid;
  if (invalid || submitting.value) {
    return;
  }

  submitting.value = true;
  try {
    await createTag({ name: newName.value.trim() });
    ElMessage.success('标签已创建');
    newName.value = '';
    await fetchList();
  } catch (cause) {
    createError.value = cause instanceof Error ? cause.message : '创建失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

function openRename(row: TagVO): void {
  renaming.value = row;
  renameValue.value = row.name;
  renameError.value = '';
  renameVisible.value = true;
}

async function handleRename(): Promise<void> {
  if (!renaming.value) {
    return;
  }

  const invalid = validateTagName(renameValue.value);
  renameError.value = invalid;
  if (invalid || submitting.value) {
    return;
  }

  submitting.value = true;
  try {
    await updateTag(renaming.value.id, { name: renameValue.value.trim() });
    ElMessage.success('标签已重命名');
    renameVisible.value = false;
    await fetchList();
  } catch (cause) {
    renameError.value = cause instanceof Error ? cause.message : '重命名失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(row: TagVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除标签「${row.name}」吗？将从 ${row.toolCount} 个工具上移除该标签，不会删除这些工具。`,
      '删除标签',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  try {
    await deleteTag(row.id);
    ElMessage.success(`标签「${row.name}」已删除，相关工具未受影响`);
    await fetchList();
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '删除失败，请稍后重试');
  }
}

onMounted(() => {
  void fetchList();
});
</script>

<template>
  <div class="tb-page">
    <div v-if="canManage" class="tb-toolbar">
      <div class="tb-toolbar__create">
        <el-input v-model="newName" placeholder="标签名称（≤20 字）" maxlength="20" @keyup.enter="handleCreate" />
        <el-button type="primary" :loading="submitting" @click="handleCreate">新增标签</el-button>
      </div>
      <span class="tb-muted">共 {{ list.length }} 个标签</span>
    </div>

    <div v-if="createError" class="tb-note is-danger">{{ createError }}</div>

    <PageContainer
      :loading="loading"
      :error="error"
      :empty="isEmpty"
      empty-text="还没有任何标签"
      empty-hint="标签用于给工具分类，方便按功能检索"
      @retry="fetchList"
    >
      <el-table :data="list" row-key="id" border stripe>
        <el-table-column prop="name" label="标签名称" min-width="200" show-overflow-tooltip />
        <el-table-column label="关联工具" width="120">
          <template #default="{ row }">{{ row.toolCount }} 个</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <div class="tb-table__ops">
              <el-button v-if="canManage" link type="primary" @click="openRename(row)">重命名</el-button>
              <el-button v-if="canManage" link type="danger" @click="handleDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </PageContainer>

    <el-dialog v-model="renameVisible" title="重命名标签" width="420px" :close-on-click-modal="false">
      <el-input v-model="renameValue" maxlength="20" placeholder="标签名称" />
      <div v-if="renameError" class="tb-note is-danger tb-dialog__error">{{ renameError }}</div>

      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleRename">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tb-page {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap);
}

.tb-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tb-gap);
}

.tb-toolbar__create {
  display: flex;
  gap: var(--tb-gap-sm);
  width: 380px;
}

.tb-table__ops {
  display: flex;
  align-items: center;
  gap: var(--tb-gap-sm);
}

.tb-dialog__error {
  margin-top: var(--tb-gap);
}
</style>
