<script setup lang="ts">
import { useRouter } from 'vue-router';

const router = useRouter();

function goHome(): void {
  void router.replace({ name: 'tool-list' });
}
</script>

<template>
  <div class="tb-notfound">
    <div class="tb-card tb-notfound__card">
      <div class="tb-notfound__code">404</div>
      <p class="tb-muted">页面不存在或已被移除。</p>
      <el-button type="primary" @click="goHome">回到工具列表</el-button>
    </div>
  </div>
</template>

<style scoped>
.tb-notfound {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--tb-bg);
}

.tb-notfound__card {
  width: 320px;
  text-align: center;
}

.tb-notfound__code {
  font-size: 34px;
  font-weight: 700;
  color: var(--tb-text-3);
  margin-bottom: var(--tb-gap-sm);
}
</style>
