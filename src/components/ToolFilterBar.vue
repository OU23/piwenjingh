<script setup lang="ts">
import { reactive, watch } from 'vue';
import TagSelect, { type TagOption } from '@/components/TagSelect.vue';
import { SCOPE, SCOPE_LABEL, SORT_BY, SORT_BY_LABEL, SORT_ORDER } from '@/constants/enums';
import { FILE_EXT_OPTIONS, type ToolFilters } from '@/utils/toolQuery';

/**
 * 工具列表筛选栏（关键词 / 标签 / 格式 / 范围 / 排序）
 * 关键词回车或点"查询"触发；下拉类条件变更即时触发（见 docs/内部-前端蓝图对照.md §四）
 */
const props = defineProps<{
  filters: ToolFilters;
  tags: TagOption[];
  /** 标签字典加载失败原因：非空时在标签条件处就地提示（不阻断列表） */
  tagsHint?: string;
}>();

const emit = defineEmits<{ search: [filters: ToolFilters]; reset: [] }>();

const draft = reactive<ToolFilters>({
  keyword: props.filters.keyword,
  tagIds: [...props.filters.tagIds],
  fileExts: [...props.filters.fileExts],
  scope: props.filters.scope,
  toolType: props.filters.toolType,
  sortBy: props.filters.sortBy,
  sortOrder: props.filters.sortOrder,
});

watch(
  () => props.filters,
  (next) => {
    draft.keyword = next.keyword;
    draft.tagIds = [...next.tagIds];
    draft.fileExts = [...next.fileExts];
    draft.scope = next.scope;
    draft.toolType = next.toolType;
    draft.sortBy = next.sortBy;
    draft.sortOrder = next.sortOrder;
  },
  { deep: true },
);

// M-23：移除「下载次数」排序项（该字段实为签发次数，前端已不展示，保留排序会造成困惑）
const sortByOptions: { value: string; label: string }[] = [
  { value: SORT_BY.CREATE_TIME, label: SORT_BY_LABEL[SORT_BY.CREATE_TIME] },
  { value: SORT_BY.NAME, label: SORT_BY_LABEL[SORT_BY.NAME] },
];

const scopeOptions: { value: string; label: string }[] = [
  { value: '', label: '全部范围' },
  { value: SCOPE.DEPT, label: SCOPE_LABEL[SCOPE.DEPT] },
  { value: SCOPE.PUBLIC, label: SCOPE_LABEL[SCOPE.PUBLIC] },
];

/** 类型筛选（契约 v1.23）：文件工具 / 链接工具 */
const toolTypeOptions: { value: string; label: string }[] = [
  { value: 'ALL', label: '全部类型' },
  { value: 'FILE', label: '工具' },
  { value: 'LINK', label: '链接' },
];

function apply(): void {
  emit('search', {
    keyword: draft.keyword,
    tagIds: [...draft.tagIds],
    fileExts: [...draft.fileExts],
    scope: draft.scope,
    toolType: draft.toolType,
    sortBy: draft.sortBy,
    sortOrder: draft.sortOrder,
  });
}
</script>

<template>
  <div class="tb-filter">
    <div class="tb-filter__item is-wide">
      <span class="tb-filter__label">关键词</span>
      <el-input
        v-model="draft.keyword"
        placeholder="工具名称或简介"
        clearable
        @keyup.enter="apply"
        @clear="apply"
      />
    </div>

    <div class="tb-filter__item">
      <span class="tb-filter__label">类型</span>
      <el-select v-model="draft.toolType" placeholder="全部类型" @change="apply">
        <el-option v-for="item in toolTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
    </div>

    <div class="tb-filter__item">
      <span class="tb-filter__label">标签</span>
      <TagSelect
        :model-value="draft.tagIds"
        :options="props.tags"
        :placeholder="props.tagsHint ? '标签不可用' : undefined"
        @update:model-value="
          (value: string[]) => {
            draft.tagIds = value;
            apply();
          }
        "
      />
      <span v-if="props.tagsHint" class="tb-filter__hint">{{ props.tagsHint }}（暂不能按标签筛选）</span>
    </div>

    <div class="tb-filter__item">
      <span class="tb-filter__label">格式</span>
      <el-select v-model="draft.fileExts" multiple collapse-tags placeholder="全部格式" @change="apply">
        <el-option v-for="ext in FILE_EXT_OPTIONS" :key="ext" :label="`.${ext}`" :value="ext" />
      </el-select>
    </div>

    <div class="tb-filter__item">
      <span class="tb-filter__label">范围</span>
      <el-select v-model="draft.scope" placeholder="全部范围" @change="apply">
        <el-option v-for="item in scopeOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
    </div>

    <div class="tb-filter__item tb-filter__item--sort">
      <span class="tb-filter__label">排序</span>
      <div class="tb-filter__sorts">
        <el-select v-model="draft.sortBy" class="tb-filter__sort" @change="apply">
          <el-option v-for="item in sortByOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="draft.sortOrder" class="tb-filter__order" @change="apply">
          <el-option :label="'降序'" :value="SORT_ORDER.DESC" />
          <el-option :label="'升序'" :value="SORT_ORDER.ASC" />
        </el-select>
      </div>
    </div>

    <div class="tb-filter__actions">
      <el-button type="primary" @click="apply">查询</el-button>
      <el-button @click="emit('reset')">重置</el-button>
      <!-- 供页面注入额外动作（如"批量删除"，选中后出现） -->
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.tb-filter {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--tb-gap);
  padding: var(--tb-gap);
  background: var(--tb-panel-2);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
}

.tb-filter__item {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap-xs);
  width: 190px;
}

.tb-filter__item.is-wide {
  width: 260px;
}

.tb-filter__label {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}

.tb-filter__hint {
  font-size: var(--tb-font-xs);
  line-height: 1.4;
  color: var(--tb-danger);
}

/* 排序项：两个下拉并排放一行，避免堆叠把项撑高、在 flex-wrap 下错位 */
.tb-filter__item--sort {
  width: auto;
}

.tb-filter__sorts {
  display: flex;
  gap: 4px;
}

.tb-filter__sort {
  width: 118px;
}

.tb-filter__order {
  width: 88px;
}

.tb-filter__actions {
  display: flex;
  gap: var(--tb-gap-sm);
}
</style>
