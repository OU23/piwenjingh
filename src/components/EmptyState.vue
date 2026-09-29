<script setup lang="ts">
/**
 * 空态 / 错误态提示（列表页四态的其中两态）
 * 文案要求：可操作——告诉用户下一步能做什么，而不是"操作失败"。
 */
const props = withDefaults(
  defineProps<{
    variant?: 'empty' | 'error';
    text: string;
    hint?: string;
    actionText?: string;
  }>(),
  {
    variant: 'empty',
    hint: '',
    actionText: '',
  },
);

const emit = defineEmits<{ action: [] }>();
</script>

<template>
  <div class="tb-empty" :class="`is-${props.variant}`">
    <div class="tb-empty__text">{{ props.text }}</div>
    <div v-if="props.hint" class="tb-empty__hint">{{ props.hint }}</div>
    <slot name="action">
      <el-button v-if="props.actionText" :type="props.variant === 'error' ? 'primary' : 'default'" @click="emit('action')">
        {{ props.actionText }}
      </el-button>
    </slot>
  </div>
</template>

<style scoped>
.tb-empty {
  text-align: center;
  padding: 40px var(--tb-gap);
  color: var(--tb-text-2);
  font-size: var(--tb-font-sm);
  line-height: 2;
}

.tb-empty.is-error .tb-empty__text {
  color: var(--tb-danger);
}

.tb-empty__hint {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
  margin-bottom: var(--tb-gap);
}
</style>
