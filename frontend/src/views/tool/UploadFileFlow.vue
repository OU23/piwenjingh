<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import ToolMetaForm from '@/components/ToolMetaForm.vue';
import UploadFilePicker from '@/components/UploadFilePicker.vue';
import UploadProgressPanel from '@/components/UploadProgressPanel.vue';
import { UploadCanceledError, createFileIdentifier, type UploadProgress, type UploadResult, type UploadTask } from '@/api/chunkUpload';
import type { TagVO } from '@/api/tag';
import { submitTool } from '@/api/toolSubmit';
import type { ID } from '@/api/types';
import { loadUploadContext } from '@/api/uploadContext';
import type { UploadMode } from '@/api/uploadMode';
import { createUploadTask } from '@/api/uploadTask';
import { PERMISSION } from '@/constants/permissions';
import { useUploadDraftStore, type PendingUpload } from '@/stores/uploadDraft';
import { useUserStore } from '@/stores/user';
import { formatFileSize } from '@/utils/format';
import { fileExtFromName, nameFromFileName, type ToolMetaMode, type ToolMetaValues } from '@/utils/toolMetaValidation';
import { STEPS, emptyProgress, type StepName } from './uploadSteps';
import { resolveTrashConflict } from './uploadTrashConflict';
import { createPendingProgressRecorder } from './uploadPending';
import { useUploadLeaveGuard } from './uploadLeaveGuard';
import { useAssignDeptOptions } from './deptOptions';

/**
 * **文件工具**上传流程：选择文件 → 上传 → 填写信息 → 完成（P3 计划 T1–T8）
 * 两种入口：新建（列表页进入）/ 追加版本（详情页带 toolId 进入）。
 * 唯一分片链路，小文件同样走分片（见 docs/内部-前端蓝图对照.md §四）。
 *
 * ⚠️ M-25 起本组件由 `UploadView.vue`（类型分流壳）渲染；链接工具走 `ToolLinkCreate.vue`。
 */
const route = useRoute();
const router = useRouter();

const step = ref<StepName>('pick');
/** 上传通道（M-23）：由 /api/upload/mode 决定，读取失败时兜底 server（经服务器中转始终可用） */
const uploadMode = ref<UploadMode>('server');
/** 生效分片大小（字节）：唯一来源是后端 mode.partSize（全局设置 chunkSize） */
const partSize = ref(0);
const maxSize = ref(0);
const maxSizeError = ref('');
const pickError = ref('');
const tags = ref<TagVO[]>([]);
const tagsError = ref('');
const progress = ref<UploadProgress>(emptyProgress());
const paused = ref(false);
const merging = ref(false);
const resumedFrom = ref(0);
const uploadResult = ref<UploadResult | null>(null);
const metaInitial = ref<Partial<ToolMetaValues> | null>(null);
const submitting = ref(false);
const submitError = ref('');
const doneMessage = ref('');
const toolName = ref('');
const submittedToolId = ref<ID | null>(null);
const userStore = useUserStore();
/** 刷新时"上次上传未完成"的提示，仅在选择文件步骤展示 */
const pendingUploadHint = ref<PendingUpload | null>(null);

function readToolId(): ID | undefined {
  const value = route.query.toolId;
  return typeof value === 'string' && value ? value : undefined;
}
const toolId = ref<ID | undefined>(readToolId());
const mode = computed<ToolMetaMode>(() => (toolId.value ? 'version' : 'create'));
const activeIndex = computed(() => STEPS.findIndex((item) => item.key === step.value));

let task: UploadTask | null = null;

/** 「未完成上传」进度记录器（只写盘、不参与调度；同一进度不重复写） */
const pendingProgress = createPendingProgressRecorder();

/** 归属部门下拉（M-25）：仅 tool:assign 时渲染，数据复用 `GET /api/depts?status=1` */
const { canAssign, deptOptions } = useAssignDeptOptions();

async function startUpload(selected: File): Promise<void> {
  task?.stop();
  pickError.value = '';
  submitError.value = '';
  // 换了新文件：清掉上一个文件的填写遗留与提交错误（名称/简介/标签不跨文件携带）
  // ⚠️ 追加版本模式例外：名称 / 简介 / 可见范围等属**工具级**信息（来自时详情接口），
  //    若一起清空，ToolMetaForm 的名称只读区会变成"—"，提交也会丢工具级预填。
  if (mode.value === 'create') {
    metaInitial.value = null;
  } else {
    metaInitial.value = { ...(metaInitial.value ?? {}) };
  }
  uploadResult.value = null;
  resumedFrom.value = 0;
  paused.value = false;
  merging.value = false;
  progress.value = emptyProgress(selected.size);
  step.value = 'upload';

  // 开始新上传：上一轮残留的草稿作废（否则刷新时会被旧草稿劫持到"填写信息"步骤）
  const draftStore = useUploadDraftStore();
  draftStore.clear();
  draftStore.clearPending();
  pendingUploadHint.value = null;
  pendingProgress.reset();

  // 标识需读文件头/中/尾采样，异步算好后传入（补充说明 N-05）
  const identifier = await createFileIdentifier(selected);

  const running = await createUploadTask({
    mode: uploadMode.value,
    file: selected,
    identifier,
    partSize: partSize.value,
    onProgress: (next) => {
      progress.value = next;
      // 分片传完 → 进入服务端合并（直传则为 OSS 合并 + 完成登记）
      merging.value = next.totalChunks > 0 && next.uploadedChunks >= next.totalChunks;
      // 记录进度：刷新后可提示"重新选择同一文件从断点继续"
      pendingProgress.record(selected.name, next);
    },
    onChecked: (uploadedChunks) => {
      resumedFrom.value = uploadedChunks;
    },
  });
  task = running;

  try {
    const result = await running.run();
    uploadResult.value = result;
    metaInitial.value = {
      // 保留原有字段（追加版本模式下 onMounted 已填入工具信息，不能被整体覆盖）
      ...(metaInitial.value ?? {}),
      // T13：新建模式用「去掉后缀的文件名」预填名称（可修改）；追加版本时名称只读、属工具级
      ...(mode.value === 'create' ? { name: nameFromFileName(result.fileName) } : {}),
      // M-25：新建时归属部门默认自己部门（仅 tool:assign 可见/可改）
      ...(mode.value === 'create' ? { deptId: userStore.user?.deptId ?? '' } : {}),
      fileExt: fileExtFromName(result.fileName),
    };
    step.value = 'meta';
    draftStore.clearPending(); // 已上传完成："未完成上传"的记录作废
    // 进入填写步骤即落一份草稿（无需先改字段）：切走/刷新后文件不用重传，直接停在填写步骤
    draftStore.save({
      mode: mode.value,
      toolId: toolId.value,
      toolName: toolName.value,
      step: 'meta',
      uploadResult: result,
      meta: { ...metaInitial.value },
    });
  } catch (error) {
    if (error instanceof UploadCanceledError) {
      return; // 取消流程已处理
    }
    ElMessage.error(error instanceof Error ? error.message : '上传失败，请稍后重试');
    step.value = 'pick';
  } finally {
    merging.value = false;
    if (task === running) {
      task = null;
    }
  }
}

function handlePicked(selected: File): void {
  void startUpload(selected);
}

/**
 * 暂停：界面状态 + **真正通知上传任务**（中止在途分片，点即停）。
 * 只改 `paused` ref 不会停上传——调度由 `ChunkUploadTask` 决定，必须显式调用。
 */
function handlePause(): void {
  paused.value = true;
  task?.pause();
}

/** 继续：恢复界面状态并唤醒上传任务（被中止的分片会重新上传） */
function handleResume(): void {
  paused.value = false;
  task?.resume();
}

async function handleCancel(): Promise<void> {
  try {
    await ElMessageBox.confirm('取消后服务端已上传的分片会被清理，下次需要重新上传。确定取消吗？', '取消上传', {
      type: 'warning',
      confirmButtonText: '确定取消',
      cancelButtonText: '继续上传',
    });
  } catch {
    return;
  }

  await task?.cancel();
  task = null;
  step.value = 'pick';
  progress.value = emptyProgress();
  const draftStore = useUploadDraftStore();
  draftStore.clear();
  draftStore.clearPending();
  ElMessage.info('已取消上传，服务端分片已清理');
}

/**
 * 放弃本次"填写信息"：回到选择文件步骤。
 * ⚠️ 必须清掉草稿——否则用户已放弃这一次填写，之后刷新页面时旧草稿仍会让页面跳回
 * "填写信息"并带着上一份工具的名称/简介/标签（即本次报告的问题）。
 */
function handleMetaCancel(): void {
  const draftStore = useUploadDraftStore();
  draftStore.clear();
  draftStore.clearPending();
  uploadResult.value = null;
  metaInitial.value = null;
  submitError.value = '';
  progress.value = emptyProgress();
  step.value = 'pick';
}

async function handleSubmit(values: ToolMetaValues): Promise<void> {
  if (!uploadResult.value || submitting.value) {
    return; // 连点提交只生效一次（验收 C7）
  }

  submitting.value = true;
  submitError.value = '';

  try {
    const outcome = await submitTool({
      mode: mode.value,
      values,
      upload: uploadResult.value,
      toolId: toolId.value,
    });

    // 回收站里有同上传人同名工具 → 提示"恢复旧工具 / 删除旧工具"（契约 v1.21 §9.4）
    if (outcome.kind === 'in-trash') {
      await handleTrashConflict(outcome, values);
      return;
    }

    // @deprecated v1.21 起同名转版本已恢复，不再产生；保留兜底
    if (outcome.kind === 'name-blocked') {
      submitError.value = `名称「${values.name}」已被占用，请更换名称后重新提交；文件已上传，无需重传。`;
      return;
    }

    submittedToolId.value = outcome.toolId;
    doneMessage.value =
      outcome.kind === 'created'
        ? `工具「${values.name}」已创建，当前版本 ${values.version}。`
        : `新版本 ${values.version} 已提交。`;
    step.value = 'done';
    useUploadDraftStore().clear();
  } catch (error) {
    // 提交失败：保留表单与上传结果，可直接重试而不必重传文件（验收 B4）
    submitError.value = error instanceof Error ? error.message : '提交失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

/**
 * 回收站同名冲突（契约 §9.4 v1.21）：交互与接口调用见 `uploadTrashConflict.ts`。
 * 这里只提供"是否可彻底删除"与"结果文案写回"两个钩子。
 */
async function handleTrashConflict(
  outcome: { toolId: ID | null },
  values: ToolMetaValues,
): Promise<void> {
  await resolveTrashConflict(outcome, values, {
    canPurge: userStore.has(PERMISSION.TOOL_DELETE),
    setMessage: (message) => {
      submitError.value = message;
    },
  });
}

/** 填写过程中的每次变更都写入草稿：侧边栏切走 / 刷新页面后可续填，不必重传 */
function onMetaChange(values: ToolMetaValues): void {
  if (step.value !== 'meta' || !uploadResult.value) {
    return;
  }
  useUploadDraftStore().save({
    mode: mode.value,
    toolId: toolId.value,
    toolName: toolName.value,
    step: 'meta',
    uploadResult: uploadResult.value,
    meta: { ...values },
  });
}

function restart(): void {
  step.value = 'pick';
  uploadResult.value = null;
  metaInitial.value = null; // 再传一个：同样不带上一份工具的填写遗留
  submittedToolId.value = null;
  submitError.value = '';
  progress.value = emptyProgress();
  pendingUploadHint.value = null;
  const draftStore = useUploadDraftStore();
  draftStore.clear();
  draftStore.clearPending();
}

function goDetail(): void {
  if (submittedToolId.value) {
    void router.push({ name: 'tool-detail', params: { id: submittedToolId.value } });
    return;
  }
  void router.push({ name: 'tool-list' });
}

/** 上传中的离开保护（浏览器刷新 + 路由离开）：实现见 `uploadLeaveGuard.ts` */
useUploadLeaveGuard({
  isUploading: () => step.value === 'upload',
  stop: () => task?.stop(),
});

onMounted(async () => {
  // 先恢复草稿（同步，不等接口）：即使标签/上限接口失败或慢，续填也不受影响
  const draftStore = useUploadDraftStore();
  const resumable = draftStore.isResumable();

  if (resumable) {
    // 续填：侧边栏切走 / 刷新页面后回到"填写信息"步骤，已上传的文件无需重传（fileKey 已在服务端）
    const d = draftStore.draft!;
    toolId.value = d.toolId;
    toolName.value = d.toolName;
    uploadResult.value = d.uploadResult;
    metaInitial.value = d.meta;
    step.value = 'meta';
    draftStore.clearPending(); // 已到填写步骤："未完成上传"的记录作废
  } else {
    // 没有可续填的草稿 → 一律回到选择文件步骤，并清掉残留草稿
    // （含"上一轮已放弃填写"的旧草稿，否则它会劫持页面、带着上一份工具的信息跳到"填写信息"）
    draftStore.clear();
    // 若是"上传中刷新"：给出续传提示（File 无法序列化，需重新选择同一文件）
    pendingUploadHint.value = draftStore.pendingUpload;
  }

  try {
    const context = await loadUploadContext(toolId.value);
    uploadMode.value = context.mode === 'direct' ? 'direct' : 'server';
    partSize.value = context.partSize;
    maxSize.value = context.maxFileSize;
    maxSizeError.value = context.maxFileSizeError;
    tags.value = context.tags;
    tagsError.value = context.tagsError;

    if (!resumable && context.tool) {
      toolName.value = context.tool.name;
      metaInitial.value = {
        name: context.tool.name,
        fileExt: context.tool.fileExt,
        platform: context.tool.platform,
        dependency: context.tool.dependency,
        intro: context.tool.intro,
        scope: context.tool.scope,
        tagIds: context.tool.tagIds,
      };
    }
  } catch {
    /* 上下文加载失败不阻断续填与选文件：错误提示已由 loadUploadContext 内部落到 *Error 字段 */
  }
});

onBeforeUnmount(() => {
  task?.stop(); // 卸载时停掉在途请求，不留后台任务（验收 A10）
  const draftStore = useUploadDraftStore();
  // 仅保留"填写步骤 + 文件已上传"的草稿，其余情况（进行中/已完成/刚进入）清空
  if (!(step.value === 'meta' && uploadResult.value)) {
    draftStore.clear();
  }
  // "未完成上传"的记录只在**刷新**后有意义：刷新不会触发本钩子（页面直接重建），
  // 正常路由离开会，故在此作废，避免下次进入看到过期提示。
  draftStore.clearPending();
});
</script>

<template>
  <div class="tb-upload">
    <el-steps :active="activeIndex" align-center finish-status="success" class="tb-upload__steps">
      <el-step v-for="item in STEPS" :key="item.key" :title="item.title" />
    </el-steps>

    <div class="tb-card">
      <div v-if="mode === 'version'" class="tb-note is-info tb-upload__target">
        正在为工具「{{ toolName || '…' }}」上传新版本
      </div>

      <div v-if="step === 'pick' && pendingUploadHint" class="tb-note is-info tb-upload__pending">
        上次上传「{{ pendingUploadHint.fileName }}」未完成（已传 {{ pendingUploadHint.uploadedChunks }} /
        {{ pendingUploadHint.totalChunks }} 片）。重新选择<strong>同一个文件</strong>即可从断点继续，已传部分不会重传。
      </div>

      <div v-if="step === 'pick' && partSize > 0" class="tb-upload__part-size">
        分片大小 {{ formatFileSize(partSize) }}（{{ uploadMode === 'direct' ? '浏览器直传对象存储' : '经服务器上传' }}）；
        由管理员在「全局设置」中统一配置。
      </div>

      <UploadFilePicker
        v-if="step === 'pick'"
        :max-size="maxSize"
        @picked="handlePicked"
        @error="(message: string) => (pickError = message)"
      />

      <UploadProgressPanel
        v-else-if="step === 'upload'"
        :progress="progress"
        :paused="paused"
        :merging="merging"
        :resumed-from="resumedFrom"
        @pause="handlePause"
        @resume="handleResume"
        @cancel="handleCancel"
      />

      <ToolMetaForm
        v-else-if="step === 'meta'"
        :mode="mode"
        :initial="metaInitial"
        :tags="tags"
        :tags-hint="tagsError"
        :dept-options="deptOptions"
        :can-assign="canAssign"
        :file-name="uploadResult?.fileName"
        :file-size="uploadResult?.fileSize"
        :submitting="submitting"
        :error-message="submitError"
        @submit="handleSubmit"
        @cancel="handleMetaCancel"
        @change="onMetaChange"
      />

      <div v-else class="tb-upload__done">
        <div class="tb-upload__done-title">上传完成</div>
        <p class="tb-muted">{{ doneMessage }}</p>
        <div class="tb-upload__done-actions">
          <el-button type="primary" @click="goDetail">查看工具</el-button>
          <el-button @click="restart">再传一个</el-button>
        </div>
      </div>

      <div v-if="pickError" class="tb-note is-danger tb-upload__error">{{ pickError }}</div>

      <div v-if="maxSizeError" class="tb-note is-danger tb-upload__error">{{ maxSizeError }}</div>
    </div>
  </div>
</template>

<style scoped>
.tb-upload {
  display: flex;
  flex-direction: column;
  gap: var(--tb-gap-lg);
}

.tb-upload__steps {
  margin-bottom: var(--tb-gap-xs);
}

.tb-upload__target {
  margin-bottom: var(--tb-gap);
}

.tb-upload__pending {
  margin-bottom: var(--tb-gap);
}

.tb-upload__part-size {
  margin-bottom: var(--tb-gap);
  font-size: var(--tb-font-xs);
  color: var(--tb-text-3);
}

.tb-upload__done {
  text-align: center;
  padding: var(--tb-gap-xl) 0;
}

.tb-upload__done-title {
  font-size: var(--tb-font-xl);
  font-weight: 700;
  color: var(--tb-success);
  margin-bottom: var(--tb-gap-sm);
}

.tb-upload__done-actions {
  display: flex;
  justify-content: center;
  gap: var(--tb-gap-sm);
  margin-top: var(--tb-gap-lg);
}

.tb-upload__error {
  margin-top: var(--tb-gap);
}
</style>
