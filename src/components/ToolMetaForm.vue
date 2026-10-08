<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import TagSelect, { type TagOption } from '@/components/TagSelect.vue';
import { SCOPE, SCOPE_LABEL } from '@/constants/enums';
import {
  TOOL_META_LIMITS,
  createEmptyToolMeta,
  type ToolMetaMode,
  type ToolMetaValues,
} from '@/utils/toolMetaValidation';
import { formatFileSize } from '@/utils/format';

/**
 * 工具元数据表单（新建 / 追加版本 / 编辑 三模式共用）
 * 字段与上限照 docs/接口契约.md §9.4；追加版本模式只提交版本号（其余信息属工具级）。
 */
const props = withDefaults(
  defineProps<{
    mode: ToolMetaMode;
    /** 预填值（追加版本时传入已选工具的信息） */
    initial?: Partial<ToolMetaValues> | null;
    tags: TagOption[];
    /** 标签字典加载失败原因：非空时在标签处就地提示 */
    tagsHint?: string;
    /** 已上传好的文件（展示用，不含存储键） */
    fileName?: string;
    fileSize?: number;
    submitting?: boolean;
    errorMessage?: string;
  }>(),
  {
    initial: null,
    tagsHint: '',
    fileName: '',
    fileSize: 0,
    submitting: false,
    errorMessage: '',
  },
);

const emit = defineEmits<{ submit: [values: ToolMetaValues]; cancel: []; change: [values: ToolMetaValues] }>();

const form = reactive<ToolMetaValues>(createEmptyToolMeta());

const showFullForm = computed(() => props.mode !== 'version');
const scopeOptions = computed(() => [SCOPE.DEPT, SCOPE.PUBLIC].map((value) => ({ value, label: SCOPE_LABEL[value] })));

watch(
  () => props.initial,
  (next) => {
    Object.assign(form, createEmptyToolMeta(), next ?? {});
    // 避免与外部对象共享数组引用
    form.tagIds = [...(next?.tagIds ?? [])];
  },
  { immediate: true, deep: true },
);

// 任意字段变更即回传当前值，父组件据此写入草稿以支持侧边栏切走 / 刷新后续填
watch(
  form,
  () => emit('change', { ...form }),
  { deep: true },
);

function handleSubmit(): void {
  emit('submit', {
    ...form,
    name: form.name.trim(),
    version: form.version.trim(),
    intro: form.intro.trim(),
    fileExt: form.fileExt.trim().toLowerCase(),
    platform: form.platform.trim(),
    dependency: form.dependency.trim(),
    tagIds: [...form.tagIds],
  });
}
</script>

<template>
  <div class="tb-meta">
    <div v-if="props.fileName" class="tb-meta__file">
      <span class="tb-meta__file-label">已上传文件</span>
      <span>{{ props.fileName }}</span>
      <span class="tb-muted">{{ formatFileSize(props.fileSize) }}</span>
    </div>

    <div class="tb-meta__grid">
      <div v-if="showFullForm" class="tb-meta__item">
        <label class="tb-meta__label" for="meta-name">工具名称 <em>*</em></label>
        <el-input
          id="meta-name"
          v-model="form.name"
          :maxlength="TOOL_META_LIMITS.name"
          placeholder="如：数据清洗小工具"
        />
      </div>

      <div v-else class="tb-meta__item">
        <span class="tb-meta__label">工具名称</span>
        <div class="tb-meta__readonly">{{ props.initial?.name || '—' }}</div>
      </div>

      <div class="tb-meta__item">
        <label class="tb-meta__label" for="meta-version">版本号 <em>*</em></label>
        <el-input
          id="meta-version"
          v-model="form.version"
          :maxlength="TOOL_META_LIMITS.version"
          placeholder="如：v1.0"
        />
      </div>

      <template v-if="showFullForm">
        <div class="tb-meta__item">
          <span class="tb-meta__label">文件格式</span>
          <!-- 格式由上传文件名自动识别，不可手选；用只读输入框呈现，样式与同行字段一致 -->
          <el-input :model-value="form.fileExt ? `.${form.fileExt}` : '—'" readonly />
        </div>

        <div class="tb-meta__item">
          <span class="tb-meta__label">运行平台</span>
          <el-input v-model="form.platform" placeholder="如：Windows / Linux / 跨平台" />
        </div>

        <div class="tb-meta__item">
          <span class="tb-meta__label">可见范围 <em>*</em></span>
          <el-radio-group v-model="form.scope">
            <el-radio v-for="item in scopeOptions" :key="item.value" :value="item.value">{{ item.label }}</el-radio>
          </el-radio-group>
        </div>

        <div class="tb-meta__item">
          <span class="tb-meta__label">标签</span>
          <TagSelect v-model="form.tagIds" :options="props.tags" :placeholder="props.tagsHint ? '标签不可用' : undefined" />
          <span v-if="props.tagsHint" class="tb-meta__hint">{{ props.tagsHint }}（暂不能选择标签）</span>
        </div>

        <div class="tb-meta__item is-full">
          <label class="tb-meta__label" for="meta-intro">简介 <em>*</em></label>
          <el-input
            id="meta-intro"
            v-model="form.intro"
            type="textarea"
            :rows="2"
            :maxlength="TOOL_META_LIMITS.intro"
            show-word-limit
            placeholder="一句话说明这个工具是做什么的"
          />
        </div>

        <div class="tb-meta__item is-full">
          <label class="tb-meta__label" for="meta-dependency">依赖说明</label>
          <el-input
            id="meta-dependency"
            v-model="form.dependency"
            type="textarea"
            :rows="2"
            :maxlength="TOOL_META_LIMITS.dependency"
            show-word-limit
            placeholder="如：需要 Java 17、Maven 3.9"
          />
        </div>

        <div class="tb-meta__item is-full">
          <label class="tb-meta__label" for="meta-readme">使用说明</label>
          <el-input
            id="meta-readme"
            v-model="form.readme"
            type="textarea"
            :rows="6"
            :maxlength="TOOL_META_LIMITS.readme"
            show-word-limit
            placeholder="纯文本，支持换行：安装步骤、运行命令、注意事项"
          />
        </div>
      </template>
    </div>

    <div v-if="props.errorMessage" class="tb-note is-danger tb-meta__error">{{ props.errorMessage }}</div>

    <div class="tb-meta__actions">
      <el-button type="primary" :loading="props.submitting" @click="handleSubmit">
        {{ props.mode === 'version' ? '提交新版本' : '提交' }}
      </el-button>
      <el-button :disabled="props.submitting" @click="emit('cancel')">返回</el-button>
    </div>
  </div>
</template>

<style scoped>
.tb-meta__file {
  display: flex;
  align-items: center;
  gap: var(--tb-gap);
  padding: var(--tb-gap);
  margin-bottom: var(--tb-gap);
  background: var(--tb-panel-2);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius-sm);
  font-size: var(--tb-font-sm);
}

.tb-meta__file-label {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}

.tb-meta__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--tb-gap-lg);
}

.tb-meta__item {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap-xs);
  min-width: 0;
}

.tb-meta__item.is-full {
  grid-column: 1 / -1;
}

.tb-meta__label {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}

.tb-meta__hint {
  font-size: var(--tb-font-xs);
  line-height: 1.4;
  color: var(--tb-danger);
}

.tb-meta__label em {
  color: var(--tb-danger);
  font-style: normal;
}

.tb-meta__readonly {
  font-size: var(--tb-font-sm);
  padding: var(--tb-gap-xs) 0;
}

.tb-meta__error {
  margin-top: var(--tb-gap);
}

.tb-meta__actions {
  display: flex;
  gap: var(--tb-gap-sm);
  margin-top: var(--tb-gap-lg);
}
</style>
