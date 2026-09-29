<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { listUsers, type UserVO } from '@/api/user';
import { listToolGrants } from '@/api/tool';
import type { ID, ToolBriefVO, UserBriefVO } from '@/api/types';

/**
 * 工具侧「授权」弹窗（契约 §9.8，计划 P6 T4）
 * - 先定工具、再**多选用户**；与用户侧 `/api/users/{id}/tool-grants` 操作**同一份**授权关系
 * - 保存为**全量覆盖**（`PUT /api/tools/{id}/grants`，单接口）
 * - 弹窗内**不出现解释性提示条**（需求方 2026-09-24 确认）
 * - 列表：**固定高度 + 竖向滚动**；**已选项置顶保留**（搜索后不丢勾选）
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    tool?: ToolBriefVO | null;
    submitting?: boolean;
    errorMessage?: string;
  }>(),
  { tool: null, submitting: false, errorMessage: '' },
);

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  save: [payload: { userIds: ID[] }];
}>();

/** 契约 §1.5：`pageSize` 最大 100 */
const PAGE_SIZE_MAX = 100;

const granted = ref<UserBriefVO[]>([]);
const candidates = ref<UserBriefVO[]>([]);
const keyword = ref('');
const dept = ref('');
const loading = ref(false);
const loadError = ref('');

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

const title = computed(() => `授权下载 · ${props.tool?.name ?? ''}`);
const grantIds = computed(() => granted.value.map((user) => user.id));
const grantedSet = computed(() => new Set(grantIds.value));

const deptOptions = computed(() => {
  const names = new Set<string>();
  [...granted.value, ...candidates.value].forEach((user) => {
    if (user.deptName) {
      names.add(user.deptName);
    }
  });
  return [...names];
});

/** 关键词 / 部门匹配（**仅作用于候选**；已选项始终置顶保留，不参与筛选） */
function matchesCandidate(user: UserBriefVO): boolean {
  const kw = keyword.value.trim().toLowerCase();
  // T7：工具所属部门人员属无意义配对，候选阶段硬排除（与后端 T2 收敛一致）
  if (props.tool && user.deptName && props.tool.deptName && user.deptName === props.tool.deptName) {
    return false;
  }
  if (dept.value && user.deptName !== dept.value) {
    return false;
  }
  if (kw && !user.realName.toLowerCase().includes(kw) && !user.username.toLowerCase().includes(kw)) {
    return false;
  }
  return true;
}

/** 当前筛选条件下的**全部**候选（含已选） */
const visibleCandidates = computed<UserBriefVO[]>(() => candidates.value.filter(matchesCandidate));

/** 其中**尚未选中**的部分 */
const selectableCandidates = computed<UserBriefVO[]>(() =>
  visibleCandidates.value.filter((user) => !grantedSet.value.has(user.id)),
);

/** 当前结果是否已全部选中 → 按钮切换为「取消全选」 */
const allVisibleSelected = computed(
  () => visibleCandidates.value.length > 0 && selectableCandidates.value.length === 0,
);

/** 渲染列表 = 已选（置顶保留，不受搜索影响）+ 当前结果中尚未选中的部分 */
const rows = computed<UserBriefVO[]>(() => [...granted.value, ...selectableCandidates.value]);

function toBrief(user: UserVO): UserBriefVO {
  return { id: user.id, username: user.username, realName: user.realName, deptName: user.deptName };
}

async function fetchCandidates(): Promise<void> {
  const page = await listUsers({
    pageNum: 1,
    pageSize: PAGE_SIZE_MAX,
    keyword: keyword.value.trim() || undefined,
  });
  candidates.value = page.list.map(toBrief);
}

async function load(): Promise<void> {
  if (!props.tool) {
    return;
  }

  loading.value = true;
  loadError.value = '';
  try {
    const [owned] = await Promise.all([listToolGrants(props.tool.id), fetchCandidates()]);
    granted.value = [...owned];
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '授权信息加载失败，请稍后重试';
    granted.value = [];
    candidates.value = [];
  } finally {
    loading.value = false;
  }
}

/** 关键词变化后重新检索候选（已选列表不受影响） */
async function reloadCandidates(): Promise<void> {
  loading.value = true;
  loadError.value = '';
  try {
    await fetchCandidates();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '用户列表加载失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}

function toggle(user: UserBriefVO): void {
  if (grantedSet.value.has(user.id)) {
    granted.value = granted.value.filter((item) => item.id !== user.id);
    return;
  }
  granted.value = [...granted.value, user];
}

/**
 * 「全选当前结果 / 取消全选」：作用于**当前筛选结果**，一键完成（不必逐个点取消）。
 * 已全部选中时切换为「取消全选」；取消只移除当前结果里的那一批，已选中的其它项不受影响。
 */
function toggleAllVisible(): void {
  if (allVisibleSelected.value) {
    const ids = new Set(visibleCandidates.value.map((user) => user.id));
    granted.value = granted.value.filter((user) => !ids.has(user.id));
  } else {
    granted.value = [...granted.value, ...selectableCandidates.value];
  }
}

function handleSave(): void {
  emit('save', { userIds: [...grantIds.value] });
}

watch(
  () => [props.modelValue, props.tool] as const,
  ([open]) => {
    if (!open) {
      return;
    }
    keyword.value = '';
    dept.value = '';
    granted.value = [];
    candidates.value = [];
    loadError.value = '';
    void load();
  },
  { immediate: true },
);
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="640px" :close-on-click-modal="false">
    <div v-if="loading && rows.length === 0" class="tb-muted">正在加载用户…</div>

    <div v-else-if="loadError && rows.length === 0" class="tb-note is-danger">{{ loadError }}</div>

    <template v-else>
      <div class="tb-grant__head">
        <span>已授权 <strong class="tb-grant__count">{{ grantIds.length }}</strong> 个用户</span>
        <el-button v-if="visibleCandidates.length > 0" link type="primary" @click="toggleAllVisible">
          {{ allVisibleSelected ? '取消全选' : '全选当前结果' }}
        </el-button>
      </div>

      <div class="tb-grant__tools">
        <el-input
          v-model="keyword"
          placeholder="搜索账号 / 姓名"
          clearable
          @keyup.enter="reloadCandidates"
          @clear="reloadCandidates"
        />
        <el-select v-model="dept" placeholder="全部部门" clearable class="tb-grant__dept">
          <el-option v-for="name in deptOptions" :key="name" :label="name" :value="name" />
        </el-select>
      </div>

      <div v-if="loadError" class="tb-note is-danger">
        {{ loadError }}
        <el-button link type="primary" @click="reloadCandidates">重试</el-button>
      </div>

      <div v-else-if="rows.length === 0" class="tb-muted">
        {{ candidates.length === 0 ? '没有可授权的用户' : '没有匹配的用户' }}
      </div>

      <div v-else class="tb-grant__list is-scroll">
        <div
          v-for="user in rows"
          :key="user.id"
          class="tb-grant__li"
          :class="{ 'is-on': grantedSet.has(user.id) }"
          @click="toggle(user)"
        >
          <el-checkbox :model-value="grantedSet.has(user.id)" @click.stop @change="toggle(user)" />
          <span class="tb-grant__name">{{ user.realName }}</span>
          <span class="tb-grant__acct">{{ user.username }}</span>
          <span class="tb-grant__meta">{{ user.deptName }}</span>
        </div>
      </div>

      <div v-if="props.errorMessage" class="tb-note is-danger tb-grant__error">{{ props.errorMessage }}</div>
    </template>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="props.submitting" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tb-grant__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--tb-font-xs);
  color: var(--tb-text-2);
  margin-bottom: 10px;
}

.tb-grant__count {
  color: var(--tb-success);
  font-weight: 600;
}

.tb-grant__tools {
  display: flex;
  gap: var(--tb-gap-sm);
  margin-bottom: 9px;
}

.tb-grant__dept {
  width: 160px;
  flex-shrink: 0;
}

/* 固定高度 + 竖向滚动（不使用"加载更多"） */
.tb-grant__list {
  max-height: 230px;
  overflow-y: auto;
  overscroll-behavior: contain;
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius-sm);
  background: var(--tb-panel-3);
  scrollbar-width: thin;
  scrollbar-color: #2c3550 transparent;
}

.tb-grant__list::-webkit-scrollbar {
  width: 8px;
}

.tb-grant__list::-webkit-scrollbar-track {
  background: transparent;
}

.tb-grant__list::-webkit-scrollbar-thumb {
  background: #2c3550;
  border-radius: 4px;
  border: 2px solid var(--tb-panel-3);
}

.tb-grant__li {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 11px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.045);
  cursor: pointer;
}

.tb-grant__li:last-child {
  border-bottom: none;
}

.tb-grant__li:hover {
  background: rgba(255, 255, 255, 0.035);
}

.tb-grant__li.is-on {
  background: rgba(0, 212, 170, 0.05);
}

.tb-grant__li.is-on::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--tb-success);
}

.tb-grant__name {
  font-size: var(--tb-font-xs);
  color: var(--tb-text);
  white-space: nowrap;
}

.tb-grant__acct {
  font-size: 11px;
  color: var(--tb-text-3);
}

.tb-grant__meta {
  margin-left: auto;
  font-size: 11px;
  color: var(--tb-text-3);
  white-space: nowrap;
}

.tb-grant__error {
  margin-top: var(--tb-gap);
}
</style>
