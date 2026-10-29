<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import UploadFileFlow from './UploadFileFlow.vue';
import ToolLinkCreate from './ToolLinkCreate.vue';

/**
 * 上传入口（M-25 / P20 T5）：按类型分流
 * - **文件工具** → `UploadFileFlow`（选文件 → 分片上传 → 填信息 → 完成）
 * - **链接工具** → `ToolLinkCreate`（只有网址，无文件、无版本）
 *
 * 追加版本（`?toolId=`，从详情页进入）**只有文件流程**，不显示类型切换。
 */
const route = useRoute();
const isVersionMode = computed(() => typeof route.query.toolId === 'string' && Boolean(route.query.toolId));
const type = ref<'file' | 'link'>('file');
</script>

<template>
  <div class="tb-upload-page">
    <el-radio-group v-if="!isVersionMode" v-model="type" class="tb-upload-page__tabs">
      <el-radio-button value="file">上传工具</el-radio-button>
      <el-radio-button value="link">上传链接</el-radio-button>
    </el-radio-group>

    <UploadFileFlow v-if="type === 'file'" />
    <ToolLinkCreate v-else />
  </div>
</template>

<style scoped>
.tb-upload-page {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap);
}

.tb-upload-page__tabs {
  align-self: flex-start;
}
</style>
