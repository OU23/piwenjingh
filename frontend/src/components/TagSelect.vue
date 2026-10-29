<script setup lang="ts">
/** 标签多选（工具筛选、工具元数据表单共用） */
export interface TagOption {
  id: string;
  name: string;
}

const props = withDefaults(
  defineProps<{
    /** 已选标签 ID 列表 */
    modelValue: string[];
    options: TagOption[];
    placeholder?: string;
    /** 无匹配项时的提示（如标签字典为空） */
    emptyText?: string;
  }>(),
  {
    placeholder: '请选择标签',
    emptyText: '暂无可选标签',
  },
);

const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>();

function handleChange(value: string[]): void {
  emit('update:modelValue', value);
}
</script>

<template>
  <el-select
    :model-value="props.modelValue"
    multiple
    filterable
    collapse-tags
    collapse-tags-tooltip
    :placeholder="props.placeholder"
    :no-data-text="props.emptyText"
    style="width: 100%"
    @change="handleChange"
  >
    <el-option v-for="option in props.options" :key="option.id" :label="option.name" :value="option.id" />
  </el-select>
</template>
