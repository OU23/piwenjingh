<script setup lang="ts">
import { computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import DownloadButton from '@/components/DownloadButton.vue';
import { deleteToolVersion } from '@/api/tool';
import type { ID } from '@/api/types';
import type { ToolVersionVO } from '@/api/tool';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import { formatDateTime, formatFileSize } from '@/utils/format';

/**
 * 工具版本列表：按上传时间倒序（由接口返回顺序保证），标记最新版，可按版本下载 / **永久删除**
 * 见 docs/接口契约.md §9.3 ToolVersionVO、§9.6 v1.22（历史版本物理删除、不可恢复）
 */
const props = defineProps<{ toolId: ID; versions: ToolVersionVO[]; uploaderId?: ID }>();

const emit = defineEmits<{ downloaded: []; deleted: [] }>();

const userStore = useUserStore();

/**
 * 版本删除入口显隐（契约 v1.21 §9.6）：上传人本人 / 双权限管理员 / 全局管理员。
 * 只是体验层，后端 `requireEditable` 才是权威。
 */
const canManage = computed(() => {
  if (userStore.has(PERMISSION.SYS_SETTING)) {
    return true;
  }
  if (userStore.has(PERMISSION.USER_MANAGE) && userStore.has(PERMISSION.USER_GRANT)) {
    return true;
  }
  const me = userStore.user?.userId;
  return me != null && props.uploaderId != null && String(props.uploaderId) === String(me);
});

async function handleDelete(row: ToolVersionVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除版本「${row.version}」吗？该版本及其文件将被永久删除，不可恢复。`,
      '永久删除版本',
      { type: 'warning', confirmButtonText: '永久删除', cancelButtonText: '取消' },
    );
  } catch {
    return; // 用户取消
  }
  try {
    await deleteToolVersion(props.toolId, row.id);
    ElMessage.success(`版本「${row.version}」已永久删除`);
    emit('deleted');
  } catch (error) {
    // 含后端 1005「仅剩一个版本，请直接删除整个工具」
    ElMessage.error(error instanceof Error ? error.message : '删除失败，请稍后重试');
  }
}
</script>

<template>
  <el-table :data="props.versions" row-key="id" border>
    <el-table-column label="版本" width="130">
      <template #default="{ row }">
        <span class="tb-ver__no">{{ row.version }}</span>
        <el-tag v-if="row.isLatest" size="small" type="success" class="tb-ver__latest">最新</el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
    <el-table-column label="格式" width="90">
      <template #default="{ row }">{{ row.fileExt ? `.${row.fileExt}` : '—' }}</template>
    </el-table-column>
    <el-table-column label="大小" width="100">
      <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
    </el-table-column>
    <el-table-column prop="uploaderName" label="上传人" width="110" />
    <el-table-column label="上传时间" width="170">
      <template #default="{ row }">{{ formatDateTime(row.uploadTime) }}</template>
    </el-table-column>
    <!-- M-23：不再展示「下载次数」（该字段实为签发次数，非下载成功次数，见方案 §2.7） -->
    <el-table-column label="操作" width="170" fixed="right">
      <template #default="{ row }">
        <DownloadButton
          v-if="userStore.has(PERMISSION.TOOL_DOWNLOAD)"
          :tool-id="props.toolId"
          :version-id="row.id"
          :suggested-name="row.fileName"
          @done="emit('downloaded')"
        />
        <el-button v-if="canManage" link type="danger" @click="handleDelete(row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.tb-ver__no {
  margin-right: var(--tb-gap-xs);
}

.tb-ver__latest {
  vertical-align: middle;
}
</style>
