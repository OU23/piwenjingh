<script setup lang="ts">
/** 页面骨架占位：路由与布局已就位，页面本体在对应阶段实现（见 docs/项目全景.md 阶段表） */
const props = defineProps<{
  title: string;
  stage: string;
}>();
</script>

<template>
  <div class="tb-card">
    <h2 class="tb-title">{{ props.title }}</h2>
    <p class="tb-muted">页面骨架已就位，功能将在 {{ props.stage }} 阶段实现。</p>
  </div>
</template>
