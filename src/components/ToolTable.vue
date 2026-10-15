<script setup lang="ts">
import { computed } from 'vue';
import DownloadButton from '@/components/DownloadButton.vue';
import { SCOPE, SCOPE_LABEL, type ScopeValue } from '@/constants/enums';
import { PERMISSION } from '@/constants/permissions';
import type { ToolVO } from '@/api/tool';
import { useUserStore } from '@/stores/user';
import { formatDateTime, formatFileSize } from '@/utils/format';

/**
 * 工具列表表格（列口径见 docs/接口契约.md §9.3 ToolVO）
 * 下载 / 删除入口按权限点显隐；显隐只影响体验，后端判权为最终依据（principles.md R5）。
 */
const props = withDefaults(defineProps<{ list: ToolVO[]; selectable?: boolean }>(), { selectable: false });

const emit = defineEmits<{
  downloaded: [];
  /** 行内删除（软删除，进回收站） */
  delete: [row: ToolVO];
  /** 行内「授权」（跨部门定向授权，契约 §9.8） */
  grant: [row: ToolVO];
  /** 行内「可见范围」变更（需求 11 / P14，DEPT ↔ PUBLIC） */
  'scope-change': [row: ToolVO, scope: ScopeValue];
  /** 多选变化（供批量删除） */
  'selection-change': [rows: ToolVO[]];
}>();

const userStore = useUserStore();

const canDelete = computed(() => userStore.has(PERMISSION.TOOL_DELETE));
const canGrant = computed(() => userStore.has(PERMISSION.USER_GRANT));
/**
 * 「可见范围」入口显隐（契约 v1.15 / P15）：仅**同时持 `user:manage` + `user:grant`** 渲染。
 * ⚠️ 原 P14 口径 `tool:upload` 已作废（过宽：任何可上传者都能改可见范围）。
 * 显隐只是体验层，后端 `ToolWriteService.requireEditable` 才是权威（R5）。
 */
const canChangeScope = computed(() => userStore.has(PERMISSION.USER_MANAGE) && userStore.has(PERMISSION.USER_GRANT));

const MAX_VISIBLE_TAGS = 3;

function scopeLabel(scope: string): string {
  return SCOPE_LABEL[scope as ScopeValue] ?? scope;
}

/** 保存对话框的建议文件名：工具名 + 版本 + 后缀 */
function suggestedName(row: ToolVO): string {
  const ext = row.fileExt ? `.${row.fileExt}` : '';
  return `${row.name}-${row.latestVersion}${ext}`;
}
</script>

<template>
  <el-table
    :data="props.list"
    row-key="id"
    border
    stripe
    @selection-change="(rows: ToolVO[]) => emit('selection-change', rows)"
  >
    <el-table-column v-if="props.selectable" type="selection" width="46" />
    <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
    <el-table-column prop="latestVersion" label="版本" width="90" />
    <el-table-column label="格式" width="90">
      <template #default="{ row }">
        <span>{{ row.fileExt ? `.${row.fileExt}` : '—' }}</span>
      </template>
    </el-table-column>
    <el-table-column label="标签" min-width="160">
      <template #default="{ row }">
        <template v-if="row.tags.length">
          <el-tag v-for="tag in row.tags.slice(0, MAX_VISIBLE_TAGS)" :key="tag.id" size="small" class="tb-table__tag">
            {{ tag.name }}
          </el-tag>
          <span v-if="row.tags.length > MAX_VISIBLE_TAGS" class="tb-table__more">+{{ row.tags.length - MAX_VISIBLE_TAGS }}</span>
        </template>
        <span v-else class="tb-muted">—</span>
      </template>
    </el-table-column>
    <el-table-column label="范围" width="110">
      <template #default="{ row }">{{ scopeLabel(row.scope) }}</template>
    </el-table-column>
    <el-table-column prop="deptName" label="归属部门" width="110" show-overflow-tooltip />
    <el-table-column label="大小" width="100">
      <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
    </el-table-column>
    <!-- M-23：不再展示「下载次数」（该字段实为签发次数，非下载成功次数，见方案 §2.7） -->
    <el-table-column label="更新时间" width="170">
      <template #default="{ row }">{{ formatDateTime(row.updateTime) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="320" fixed="right">
      <template #default="{ row }">
        <div class="tb-table__ops">
          <router-link class="tb-table__link" :to="{ name: 'tool-detail', params: { id: row.id } }">详情</router-link>
          <DownloadButton
            v-if="userStore.has(PERMISSION.TOOL_DOWNLOAD)"
            :tool-id="row.id"
            :suggested-name="suggestedName(row)"
            @done="emit('downloaded')"
          />
          <el-button v-if="canGrant && row.scope !== 'PUBLIC'" link type="primary" @click="emit('grant', row)">授权</el-button>
          <!-- 可见范围变更（需求 11 / P14）：仅持 tool:upload 显示；当前档位置灰，选另一档触发变更 -->
          <el-dropdown
            v-if="canChangeScope"
            trigger="click"
            @command="(scope: ScopeValue) => emit('scope-change', row, scope)"
          >
            <el-button link type="primary">可见范围</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :command="SCOPE.DEPT" :disabled="row.scope === SCOPE.DEPT">
                  {{ SCOPE_LABEL.DEPT }}
                </el-dropdown-item>
                <el-dropdown-item :command="SCOPE.PUBLIC" :disabled="row.scope === SCOPE.PUBLIC">
                  {{ SCOPE_LABEL.PUBLIC }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button v-if="canDelete" link type="danger" @click="emit('delete', row)">删除</el-button>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.tb-table__tag {
  margin-right: var(--tb-gap-xs);
}

.tb-table__more {
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}

.tb-table__ops {
  display: flex;
  align-items: center;
  gap: var(--tb-gap);
}

.tb-table__link {
  font-size: var(--tb-font-sm);
}
</style>
