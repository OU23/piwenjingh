<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { listUserToolGrants } from '@/api/user';
import { listTools, type ToolVO } from '@/api/tool';
import type { ID, ToolBriefVO } from '@/api/types';

/**
 * 「额外可下载工具（跨部门）」折叠面板 —— `PermissionDialog` 的私有子组件（计划 P6 T2）
 * - **默认收起**；**展开时才请求**（收起状态零请求）
 * - 多选：已选项**置顶保留**（不受关键词 / 部门筛选影响，防止丢勾选）
 * - 列表**固定高度 + 竖向滚动**（不使用"加载更多"）
 * - 面板内**不出现解释性提示条**（只保留计数与错误这类必要反馈）
 *
 * 父组件在本弹窗每次打开时以 `:key` 重建本组件，故内部状态无需手动重置。
 */
const props = withDefaults(
  defineProps<{
    userId: ID;
    /** 目标用户的归属部门名（T9 从 `PermissionDialog` 透传）：用于候选阶段排除"同部门工具" */
    userDeptName?: string;
    /**
     * 已选工具 ID（受控）：父组件通过 `:model-value` 传入。
     * 必须显式声明——否则会作为 fallthrough attribute 穿透到根节点 `<el-collapse>`，
     * 劫持其 `v-model="activePanels"`，导致面板加载/勾选后自动折叠（Bug 3 修复）。
     */
    modelValue?: ID[];
  }>(),
  { userDeptName: '', modelValue: () => [] },
);

const emit = defineEmits<{
  /** 当前已选工具 ID 集合（全量） */
  'update:modelValue': [toolIds: ID[]];
  /** 首次加载成功：父组件据此判断"授权区是否被看过"，决定保存时是否提交授权 */
  loaded: [];
}>();

/** 契约 §1.5：`pageSize` 最大 100 */
const PAGE_SIZE_MAX = 100;
const PANEL = 'grants';

const activePanels = ref<string[]>([]);
const loadedOnce = ref(false);
const loading = ref(false);
const loadError = ref('');
/** 已授权工具（含回显与本次新勾选）：**始终置顶保留** */
const granted = ref<ToolBriefVO[]>([]);
const candidates = ref<ToolBriefVO[]>([]);
const keyword = ref('');
const dept = ref('');

const grantIds = computed(() => granted.value.map((tool) => tool.id));
const grantedSet = computed(() => new Set(grantIds.value));

/** 部门候选：取自"已授权 ∪ 候选"的归属部门（本地筛选，不额外请求） */
const deptOptions = computed(() => {
  const names = new Set<string>();
  [...granted.value, ...candidates.value].forEach((tool) => {
    if (tool.deptName) {
      names.add(tool.deptName);
    }
  });
  return [...names];
});

/** 关键词 / 部门匹配（**仅作用于候选**；已选项始终置顶保留，不参与筛选） */
function matchesCandidate(tool: ToolBriefVO): boolean {
  const kw = keyword.value.trim().toLowerCase();
  // T8：PUBLIC 工具与"目标用户同部门工具"属无意义配对，候选阶段硬排除（与后端 T3/T5 收敛一致）
  if (tool.scope === 'PUBLIC') {
    return false;
  }
  if (props.userDeptName && tool.deptName && tool.deptName === props.userDeptName) {
    return false;
  }
  if (dept.value && tool.deptName !== dept.value) {
    return false;
  }
  if (kw && !tool.name.toLowerCase().includes(kw)) {
    return false;
  }
  return true;
}

/** 当前筛选条件下的**全部**候选（含已选） */
const visibleCandidates = computed<ToolBriefVO[]>(() => candidates.value.filter(matchesCandidate));

/** 其中**尚未选中**的部分（「全选当前结果」的作用对象） */
const selectableCandidates = computed<ToolBriefVO[]>(() =>
  visibleCandidates.value.filter((tool) => !grantedSet.value.has(tool.id)),
);

/** 当前结果是否已全部选中 → 按钮切换为「取消全选」 */
const allVisibleSelected = computed(
  () => visibleCandidates.value.length > 0 && selectableCandidates.value.length === 0,
);

/** 渲染列表 = 已选（置顶保留）+ 当前结果中尚未选中的部分 */
const rows = computed<ToolBriefVO[]>(() => [...granted.value, ...selectableCandidates.value]);

function toBrief(tool: ToolVO): ToolBriefVO {
  return { id: tool.id, name: tool.name, deptName: tool.deptName, latestVersion: tool.latestVersion, scope: tool.scope };
}

function publish(): void {
  emit('update:modelValue', [...grantIds.value]);
}

async function fetchCandidates(): Promise<void> {
  const page = await listTools({
    pageNum: 1,
    pageSize: PAGE_SIZE_MAX,
    keyword: keyword.value.trim() || undefined,
  });
  candidates.value = page.list.map(toBrief);
}

/** **展开面板时**才加载：收起状态零请求 */
async function load(): Promise<void> {
  if (loading.value || loadedOnce.value) {
    return;
  }

  loading.value = true;
  loadError.value = '';
  try {
    const [owned] = await Promise.all([listUserToolGrants(props.userId), fetchCandidates()]);
    granted.value = [...owned];
    loadedOnce.value = true;
    emit('loaded');
    publish();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '工具列表加载失败，请稍后重试';
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
    loadError.value = error instanceof Error ? error.message : '工具列表加载失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}

function retry(): void {
  if (loadedOnce.value) {
    void reloadCandidates();
    return;
  }
  void load();
}

function toggle(tool: ToolBriefVO): void {
  if (grantedSet.value.has(tool.id)) {
    granted.value = granted.value.filter((item) => item.id !== tool.id);
  } else {
    granted.value = [...granted.value, tool];
  }
  publish();
}

/**
 * 「全选当前结果 / 取消全选」：作用于**当前筛选结果**，一键完成（不必逐个点）。
 * 已全部选中时切换为「取消全选」；取消只移除当前结果里的那一批，已选中的其它项不受影响。
 */
function toggleAllVisible(): void {
  if (allVisibleSelected.value) {
    const ids = new Set(visibleCandidates.value.map((tool) => tool.id));
    granted.value = granted.value.filter((tool) => !ids.has(tool.id));
  } else {
    granted.value = [...granted.value, ...selectableCandidates.value];
  }
  publish();
}

watch(activePanels, (names) => {
  if (names.includes(PANEL)) {
    void load();
  }
});
</script>

<template>
  <el-collapse v-model="activePanels" class="tb-perm__fold">
    <el-collapse-item :name="PANEL">
      <template #title>
        <span class="tb-perm__fold-title">额外可下载工具（跨部门）</span>
        <!-- 0 个时不显示计数：无信息量的标签只会显得突兀；有值时才是"这里配了东西"的信号 -->
        <span v-if="grantIds.length > 0" class="tb-perm__fold-count">已选 {{ grantIds.length }} 个</span>
      </template>

      <div class="tb-perm__tools">
        <el-input v-model="keyword" placeholder="搜索工具名称" clearable @keyup.enter="reloadCandidates" @clear="reloadCandidates" />
        <el-select v-model="dept" placeholder="全部部门" clearable class="tb-perm__dept">
          <el-option v-for="name in deptOptions" :key="name" :label="name" :value="name" />
        </el-select>
      </div>

      <div v-if="loading" class="tb-muted">正在加载工具…</div>

      <template v-else-if="loadError">
        <div class="tb-note is-danger">{{ loadError }}</div>
        <el-button link type="primary" @click="retry">重试</el-button>
      </template>

      <div v-else-if="rows.length === 0" class="tb-muted">
        {{ candidates.length === 0 ? '暂无可授权的工具' : '没有匹配的工具' }}
      </div>

      <template v-else>
        <div class="tb-perm__list is-scroll">
          <div
            v-for="tool in rows"
            :key="tool.id"
            class="tb-perm__li"
            :class="{ 'is-on': grantedSet.has(tool.id) }"
            @click="toggle(tool)"
          >
            <el-checkbox :model-value="grantedSet.has(tool.id)" @click.stop @change="toggle(tool)" />
            <span class="tb-perm__tool-name">{{ tool.name }}</span>
            <span class="tb-perm__tool-meta">{{ tool.deptName }} · {{ tool.latestVersion }}</span>
          </div>
        </div>
        <div v-if="visibleCandidates.length > 0" class="tb-perm__tools-foot">
          <el-button link type="primary" @click="toggleAllVisible">
            {{ allVisibleSelected ? '取消全选' : '全选当前结果' }}
          </el-button>
        </div>
      </template>
    </el-collapse-item>
  </el-collapse>
</template>

<style scoped>
.tb-perm__fold {
  margin-top: var(--tb-gap-lg);
  border-top: 1px solid var(--tb-border);
}

.tb-perm__fold :deep(.el-collapse-item__header) {
  padding-left: 0;
  font-size: var(--tb-font-sm);
}

.tb-perm__fold-title {
  font-size: var(--tb-font-sm);
  font-weight: 600;
}

/* 仅在「已选 > 0」时渲染，故恒为绿色 —— 它出现即表示"这个账号配了额外授权" */
.tb-perm__fold-count {
  margin-left: auto;
  margin-right: var(--tb-gap-sm);
  padding: 1px var(--tb-gap-sm);
  border-radius: 10px;
  font-size: var(--tb-font-xs);
  background: var(--tb-success-soft);
  color: var(--tb-success);
}

.tb-perm__tools {
  display: flex;
  gap: var(--tb-gap-sm);
  margin-bottom: 9px;
}

.tb-perm__dept {
  width: 160px;
  flex-shrink: 0;
}

/* 固定高度 + 竖向滚动（不使用"加载更多"） */
.tb-perm__list {
  max-height: 230px;
  overflow-y: auto;
  overscroll-behavior: contain;
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius-sm);
  background: var(--tb-panel-3);
  scrollbar-width: thin;
  scrollbar-color: var(--tb-border) transparent;
}

.tb-perm__list::-webkit-scrollbar {
  width: 8px;
}

.tb-perm__list::-webkit-scrollbar-track {
  background: transparent;
}

.tb-perm__list::-webkit-scrollbar-thumb {
  background: var(--tb-border);
  border-radius: 4px;
  border: 2px solid var(--tb-panel-3);
}

.tb-perm__li {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: var(--tb-gap-sm) var(--tb-gap);
  border-bottom: 1px solid var(--tb-border-soft);
  cursor: pointer;
}

.tb-perm__li:last-child {
  border-bottom: none;
}

.tb-perm__li:hover {
  background: var(--tb-row-hover);
}

/* 已选项：左侧绿色标记，一眼可辨 */
.tb-perm__li.is-on {
  background: var(--tb-success-soft);
}

.tb-perm__li.is-on::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--tb-success);
}

.tb-perm__tool-name {
  font-size: var(--tb-font-xs);
  color: var(--tb-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tb-perm__tool-meta {
  margin-left: auto;
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
  white-space: nowrap;
}

.tb-perm__tools-foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}
</style>
