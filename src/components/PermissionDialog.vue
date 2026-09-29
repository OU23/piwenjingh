<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import PermissionGrantPanel from '@/components/PermissionGrantPanel.vue';
import { listPermissions, type PermissionGroupVO, type PermissionItemVO } from '@/api/permission';
import { getUserPermissions, type UserVO } from '@/api/user';
import type { ID } from '@/api/types';

/**
 * 权限分配弹窗（契约 §五 / §六 / §6.1；计划 P6 T1 ~ T3）
 * - 权限点分组与清单**取自接口**（前端不硬编码）；保存为**全量覆盖**；**清空需二次确认**
 * - 跨部门工具授权以**折叠面板**承载（子组件 `PermissionGrantPanel`）：默认收起、展开才请求
 * - 弹窗内**不出现解释性提示条**（需求方 2026-09-24 确认；只保留计数、错误这类必要反馈）
 * - 视觉以 `docs/权限弹窗UI样式.html` 为准：640px、三列网格、描述收进 ⓘ 悬停提示
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    user?: UserVO | null;
    submitting?: boolean;
    errorMessage?: string;
  }>(),
  { user: null, submitting: false, errorMessage: '' },
);

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  /**
   * `toolIds === null` 表示授权面板**从未展开**（未加载过）→ 调用方**不得**提交授权变更，
   * 否则"只想改权限点"的保存会把该账号已有的授权悄悄清空。
   */
  save: [payload: { codes: string[]; toolIds: ID[] | null }];
}>();

const groups = ref<PermissionGroupVO[]>([]);
const checked = ref<string[]>([]);
const loading = ref(false);
const loadError = ref('');

/** 每次打开弹窗都重建授权面板（`key` 一变即全新状态），保证"默认收起 + 收起零请求" */
const grantPanelKey = ref(0);
const grantToolIds = ref<ID[]>([]);
const grantLoaded = ref(false);

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

interface GroupRow {
  key: string;
  name: string;
  items: PermissionItemVO[];
}

/**
 * 把**连续且仅含 1 个权限点**的分组并排成一行。
 * 按"连续单点组"判定，**不硬编码组名** —— 后端分组调整后可自适应。
 */
const groupRows = computed<GroupRow[]>(() => {
  const rows: GroupRow[] = [];
  let pending: PermissionGroupVO[] = [];

  const flush = (): void => {
    if (pending.length === 0) {
      return;
    }
    rows.push({
      key: pending.map((group) => group.groupCode).join('+'),
      name: pending.map((group) => group.groupName).join(' / '),
      items: pending.flatMap((group) => group.items),
    });
    pending = [];
  };

  groups.value.forEach((group) => {
    if (group.items.length === 0) {
      return;
    }
    if (group.items.length === 1) {
      pending.push(group);
      return;
    }
    flush();
    rows.push({ key: group.groupCode, name: group.groupName, items: group.items });
  });
  flush();

  return rows;
});

const allCodes = computed(() => groups.value.flatMap((group) => group.items.map((item) => item.code)));
const allChecked = computed(() => allCodes.value.length > 0 && checked.value.length === allCodes.value.length);

async function load(): Promise<void> {
  if (!props.user) {
    return;
  }

  loading.value = true;
  loadError.value = '';
  try {
    const [dictionary, owned] = await Promise.all([listPermissions(), getUserPermissions(props.user.id)]);
    groups.value = dictionary;
    checked.value = [...owned];
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '权限信息加载失败，请稍后重试';
    groups.value = [];
    checked.value = [];
  } finally {
    loading.value = false;
  }
}

function handleGrantChange(toolIds: ID[]): void {
  grantToolIds.value = [...toolIds];
}

function handleGrantLoaded(): void {
  grantLoaded.value = true;
}

function toggleAll(value: boolean): void {
  checked.value = value ? [...allCodes.value] : [];
}

async function handleSave(): Promise<void> {
  // 清空权限是不可逆的能力变更：必须二次确认，取消则不发起任何请求
  if (checked.value.length === 0) {
    try {
      await ElMessageBox.confirm(
        '当前未勾选任何权限点：保存后该账号将失去全部功能入口。确定清空权限吗？',
        '清空权限确认',
        { type: 'warning', confirmButtonText: '确定清空', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
  }

  emit('save', {
    codes: [...checked.value],
    // 授权面板未展开过 → 未加载 → 不提交授权变更（null 由调用方跳过）
    toolIds: grantLoaded.value ? [...grantToolIds.value] : null,
  });
}

watch(
  () => [props.modelValue, props.user] as const,
  ([open]) => {
    if (!open) {
      return;
    }
    // 每次打开都回到初始态：授权面板重建（收起、未加载、无残留勾选）
    grantPanelKey.value += 1;
    grantToolIds.value = [];
    grantLoaded.value = false;
    void load();
  },
  { immediate: true },
);
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`分配权限 · ${props.user?.realName ?? ''}`"
    width="640px"
    :close-on-click-modal="false"
  >
    <div v-if="loading" class="tb-muted">正在加载权限点…</div>

    <div v-else-if="loadError" class="tb-note is-danger">{{ loadError }}</div>

    <template v-else>
      <div class="tb-perm__head">
        <el-checkbox :model-value="allChecked" @update:model-value="(value: unknown) => toggleAll(Boolean(value))">
          全选
        </el-checkbox>
        <span class="tb-muted">已选 {{ checked.length }} / {{ allCodes.length }} 项</span>
      </div>

      <div v-for="row in groupRows" :key="row.key" class="tb-perm__group">
        <div class="tb-perm__group-name">{{ row.name }}</div>
        <el-checkbox-group v-model="checked" class="tb-perm__grid is-cols-3">
          <el-checkbox v-for="item in row.items" :key="item.code" :value="item.code" class="tb-perm__item">
            <span class="tb-perm__name">{{ item.name }}</span>
            <el-tooltip v-if="item.description" :content="item.description" placement="top" :show-after="80">
              <span class="tb-perm__info" aria-hidden="true">i</span>
            </el-tooltip>
          </el-checkbox>
        </el-checkbox-group>
      </div>

      <PermissionGrantPanel
        v-if="props.user"
        :key="grantPanelKey"
        :user-id="props.user.id"
        :user-dept-name="props.user?.deptName"
        :model-value="grantToolIds"
        @update:model-value="handleGrantChange"
        @loaded="handleGrantLoaded"
      />

      <div v-if="props.errorMessage" class="tb-note is-danger tb-perm__error">{{ props.errorMessage }}</div>
    </template>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="loading || Boolean(loadError)" :loading="props.submitting" @click="handleSave">
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tb-perm__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: var(--tb-gap-sm);
  border-bottom: 1px solid var(--tb-border);
}

.tb-perm__group {
  margin-top: 13px;
}

.tb-perm__group-name {
  font-size: var(--tb-font-xs);
  color: var(--tb-accent);
  margin-bottom: 7px;
  letter-spacing: 0.3px;
}

/* 三列网格：8 个权限点由 8 行压到 3 行 */
.tb-perm__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 14px;
}

.tb-perm__item {
  display: flex;
  align-items: center;
  height: auto;
  margin-right: 0;
  min-width: 0;
}

.tb-perm__item :deep(.el-checkbox__label) {
  display: inline-flex;
  align-items: center;
  min-width: 0;
}

.tb-perm__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 描述收进 ⓘ 悬停提示：平时不占版面 */
.tb-perm__info {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  margin-left: 3px;
  border: 1px solid var(--tb-border);
  border-radius: 50%;
  font-size: 9px;
  font-style: normal;
  line-height: 1;
  color: var(--tb-text-3);
  cursor: help;
}

.tb-perm__info:hover {
  border-color: var(--tb-accent);
  color: var(--tb-accent);
}

.tb-perm__error {
  margin-top: var(--tb-gap);
}
</style>
