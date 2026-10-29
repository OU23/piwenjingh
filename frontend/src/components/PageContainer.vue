<script setup lang="ts">
import EmptyState from './EmptyState.vue';

/**
 * 列表页四态容器：加载中 / 有数据 / 空数据 / 请求失败
 * 规范要求列表页必须四态齐全（docs/rules/02-规范细则/frontend/frontend.md）
 */
const props = withDefaults(
  defineProps<{
    loading?: boolean;
    /** 请求失败的提示文案；为空表示无错误 */
    error?: string | null;
    /** 是否空数据 */
    empty?: boolean;
    emptyText?: string;
    emptyHint?: string;
    emptyActionText?: string;
  }>(),
  {
    loading: false,
    error: null,
    empty: false,
    emptyText: '暂无数据',
    emptyHint: '',
    emptyActionText: '',
  },
);

const emit = defineEmits<{ retry: []; emptyAction: [] }>();
</script>

<template>
  <div class="tb-container">
    <div v-if="props.loading" class="tb-container__loading">
      <el-skeleton :rows="5" animated />
    </div>

    <EmptyState
      v-else-if="props.error"
      variant="error"
      :text="props.error"
      hint="请检查网络或稍后重试"
      action-text="重试"
      @action="emit('retry')"
    />

    <EmptyState
      v-else-if="props.empty"
      :text="props.emptyText"
      :hint="props.emptyHint"
      :action-text="props.emptyActionText"
      @action="emit('emptyAction')"
    />

    <slot v-else />
  </div>
</template>

<style scoped>
.tb-container {
  min-height: 120px;
}

.tb-container__loading {
  padding: var(--tb-gap);
}
</style>
