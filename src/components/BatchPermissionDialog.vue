<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { listPermissions, type PermissionGroupVO, type PermissionItemVO } from '@/api/permission';

/**
 * 批量分配权限弹窗（P13 / 需求 9；契约 v1.16 §六）。
 *
 * - 权限点分组与清单**取自接口**（前端不硬编码，`GET /api/permissions`）。
 * - 语义为**覆盖式**：以本次勾选为**最终权限全集**，入参未含的旧权限会被清除。
 *   因此弹窗**初始不勾选任何权限点**（不预填某个账号的现状）—— 预填在多人场景下语义混乱。
 * - 清空（一个都不勾）是不可逆的能力变更 → **二次确认**（与单条 `PermissionDialog` 口径一致）。
 * - 弹窗内**不出现解释性提示条**（需求方 2026-09-24 确认；只保留计数、错误这类必要反馈）。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    /** 本次将被覆盖的账号数量（仅用于文案） */
    count: number;
    submitting?: boolean;
    errorMessage?: string;
  }>(),
  { submitting: false, errorMessage: '' },
);

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  save: [payload: { codes: string[] }];
}>();

const groups = ref<PermissionGroupVO[]>([]);
const checked = ref<string[]>([]);
const loading = ref(false);
const loadError = ref('');

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

interface GroupRow {
  key: string;
  name: string;
  items: PermissionItemVO[];
}

/** 与 `PermissionDialog` 同口径：连续且仅含 1 个权限点的分组并排成一行（不硬编码组名） */
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
  loading.value = true;
  loadError.value = '';
  try {
    groups.value = await listPermissions();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '权限清单加载失败，请稍后重试';
    groups.value = [];
  } finally {
    loading.value = false;
  }
}

function toggleAll(value: boolean): void {
  checked.value = value ? [...allCodes.value] : [];
}

async function handleSave(): Promise<void> {
  // 覆盖式：未勾选 = 清空这些账号的全部权限，不可逆 → 二次确认，取消则不发请求
  if (checked.value.length === 0) {
    try {
      await ElMessageBox.confirm(
        `当前未勾选任何权限点：确定后这 ${props.count} 个账号将失去全部功能入口（原有权限会被清除）。确定清空吗？`,
        '清空权限确认',
        { type: 'warning', confirmButtonText: '确定清空', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
  }

  emit('save', { codes: [...checked.value] });
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return;
    }
    // 每次打开都回到初始态：**不预填**（覆盖式语义），并清掉上一次的错误提示
    checked.value = [];
    void load();
  },
  { immediate: true },
);
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`批量分配权限 · ${props.count} 个账号`"
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

      <div v-if="props.errorMessage" class="tb-note is-danger tb-perm__error">{{ props.errorMessage }}</div>
    </template>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="loading || Boolean(loadError)" :loading="props.submitting" @click="handleSave">
        确定
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 与 PermissionDialog 同命名空间、同视觉；弹窗内不放解释性提示条 */
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
  font-size: 10px;
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
