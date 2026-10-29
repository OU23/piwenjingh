<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { getMigrationStatus, startMigration } from '@/api/tool';
import type { MigrationTaskVO } from '@/api/types';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';

/**
 * 存量迁移到对象存储（契约 v1.19 §9.9；M-22 / F-17 · S-08）
 *
 * 交互：点「开始迁移」→ `POST /api/tools/migrate-to-oss` → 拿到 `MigrationTaskVO`。
 * **可替换性（★）**：后端本期为**同步**执行（直接回 `COMPLETED`）；
 * 一旦改成异步（`PENDING` / `RUNNING`），本组件**已经写好轮询分支** —— 前端无需改动。
 *
 * 只显示合计与失败明细；迁移**成功即删本地副本**（真正释放磁盘，见 SyncMigrationExecutor）。
 */
const userStore = useUserStore();

/** 轮询间隔：本期走不到（同步完成），为将来异步化预留 */
const POLL_INTERVAL_MS = 2000;

const canMigrate = computed(() => userStore.has(PERMISSION.SYS_SETTING));

const limit = ref<number | undefined>(undefined);
const task = ref<MigrationTaskVO | null>(null);
const busy = ref(false);
const failuresOpen = ref(false);

let timer: ReturnType<typeof setTimeout> | null = null;

function stopPolling(): void {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
}

const statusText = computed(() => {
  switch (task.value?.status) {
    case 'PENDING':
      return '排队中';
    case 'RUNNING':
      return '迁移中';
    case 'FAILED':
      return '失败';
    case 'COMPLETED':
      return '已完成';
    default:
      return '';
  }
});

const isRunning = computed(
  () => task.value?.status === 'PENDING' || task.value?.status === 'RUNNING',
);

async function pollOnce(taskId: string): Promise<void> {
  try {
    const next = await getMigrationStatus(taskId);
    task.value = next;
    if (next.status === 'PENDING' || next.status === 'RUNNING') {
      timer = setTimeout(() => void pollOnce(taskId), POLL_INTERVAL_MS);
      return;
    }
    busy.value = false;
  } catch (error) {
    busy.value = false;
    ElMessage.error(error instanceof Error ? error.message : '迁移进度查询失败，请稍后重试');
  }
}

async function handleStart(): Promise<void> {
  if (busy.value) {
    return;
  }
  busy.value = true;
  failuresOpen.value = false;
  stopPolling();

  try {
    const started = await startMigration(limit.value ?? undefined);
    task.value = started;
    if (started.status === 'PENDING' || started.status === 'RUNNING') {
      // 异步执行器（将来）：交给轮询分支，接口与结构不变
      timer = setTimeout(() => void pollOnce(started.taskId), POLL_INTERVAL_MS);
      return;
    }
    busy.value = false;
    ElMessage.success('迁移任务已完成');
  } catch (error) {
    busy.value = false;
    ElMessage.error(error instanceof Error ? error.message : '迁移发起失败，请稍后重试');
  }
}

onUnmounted(() => {
  stopPolling();
  busy.value = false;
});
</script>

<template>
  <div v-if="canMigrate" class="tb-migrate">
    <h3 class="tb-migrate__title">存量迁移到对象存储</h3>
    <p class="tb-muted">
      把已上传的文件从服务器本地迁移到对象存储。迁移<strong>成功即删除本地副本</strong>，真正释放磁盘；
      单个文件失败不影响其他文件，可重复执行（已迁移的会自动跳过）。
    </p>

    <div class="tb-migrate__form">
      <label class="tb-migrate__label" for="migrate-limit">单次条数上限</label>
      <el-input-number
        id="migrate-limit"
        v-model="limit"
        :min="1"
        :step="100"
        placeholder="不填 = 全部"
        :disabled="busy"
      />
      <el-button type="primary" :loading="busy" @click="handleStart">
        {{ busy ? '迁移中…' : '开始迁移' }}
      </el-button>
    </div>

    <el-alert
      v-if="task"
      class="tb-migrate__result"
      :type="task.failed > 0 ? 'warning' : 'success'"
      :closable="false"
      show-icon
      :title="`迁移结果：${statusText}`"
    >
      <div class="tb-migrate__counts">
        <span>总数：{{ task.total ?? '—' }}</span>
        <span>已迁移：{{ task.migrated }}</span>
        <span>失败：{{ task.failed }}</span>
        <span>跳过：{{ task.skipped }}</span>
      </div>
      <div class="tb-migrate__meta">开始：{{ task.startedAt }} ／ 结束：{{ task.finishedAt ?? '—' }}</div>
      <div v-if="task.failures.length" class="tb-migrate__failures">
        <el-button link type="primary" @click="failuresOpen = !failuresOpen">
          {{ failuresOpen ? '收起失败明细' : `展开失败明细（${task.failures.length}）` }}
        </el-button>
        <ul v-if="failuresOpen" class="tb-migrate__list">
          <li v-for="item in task.failures" :key="item.versionId">
            版本 {{ item.versionId }}：{{ item.reason }}
          </li>
        </ul>
      </div>
    </el-alert>

    <span v-if="isRunning" class="tb-muted">任务进行中，请稍候（进度自动刷新）…</span>
  </div>
</template>

<style scoped>
.tb-migrate {
  margin-top: var(--tb-gap-lg);
  padding-top: var(--tb-gap-lg);
  border-top: 1px solid var(--tb-border);
}

.tb-migrate__title {
  margin: 0 0 var(--tb-gap-xs);
  font-size: var(--tb-font);
  font-weight: 500;
  color: var(--tb-text);
}

.tb-migrate__form {
  display: flex;
  align-items: center;
  gap: var(--tb-gap-sm);
  margin-top: var(--tb-gap);
}

.tb-migrate__label {
  font-size: var(--tb-font-sm);
  color: var(--tb-text);
}

.tb-migrate__result {
  margin-top: var(--tb-gap);
}

.tb-migrate__counts {
  display: flex;
  gap: var(--tb-gap);
  flex-wrap: wrap;
}

.tb-migrate__meta {
  margin-top: var(--tb-gap-xs);
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}

.tb-migrate__failures {
  margin-top: var(--tb-gap-xs);
}

.tb-migrate__list {
  margin: var(--tb-gap-xs) 0 0;
  padding-left: 18px;
  font-size: var(--tb-font-xs);
  color: var(--tb-text-2);
}
</style>
