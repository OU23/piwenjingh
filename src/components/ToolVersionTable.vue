<script setup lang="ts">
import DownloadButton from '@/components/DownloadButton.vue';
import type { ID } from '@/api/types';
import type { ToolVersionVO } from '@/api/tool';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';
import { formatDateTime, formatFileSize } from '@/utils/format';

/**
 * 工具版本列表：按上传时间倒序（由接口返回顺序保证），标记最新版，可按版本下载
 * 见 docs/接口契约.md §9.3 ToolVersionVO
 */
const props = defineProps<{ toolId: ID; versions: ToolVersionVO[] }>();

const emit = defineEmits<{ downloaded: [] }>();

const userStore = useUserStore();
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
    <el-table-column label="操作" width="120" fixed="right">
      <template #default="{ row }">
        <DownloadButton
          v-if="userStore.has(PERMISSION.TOOL_DOWNLOAD)"
          :tool-id="props.toolId"
          :version-id="row.id"
          :suggested-name="row.fileName"
          @done="emit('downloaded')"
        />
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
