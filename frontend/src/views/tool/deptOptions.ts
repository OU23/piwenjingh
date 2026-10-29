import { computed, onMounted, ref, type ComputedRef, type Ref } from 'vue';
import { listDepts } from '@/api/dept';
import { PERMISSION } from '@/constants/permissions';
import { useUserStore } from '@/stores/user';

/**
 * 「归属部门」下拉的数据与显隐（M-25 / 需求 4、5）
 *
 * - 显隐：仅持 `tool:assign` 才需要（前端只做体验，后端仍按权限点判定）；
 * - 数据源：`GET /api/depts?status=1` —— **无权限注解、登录即可调用**（复用现有接口，不新增）；
 * - 失败不阻断页面：`deptError` 就地提示，用户仍可提交（不指定部门 = 后端取本人部门）。
 */
export interface AssignDeptOptions {
  canAssign: ComputedRef<boolean>;
  /** `{ id, name }`，可直接喂给 el-select */
  deptOptions: Ref<{ id: string; name: string }[]>;
  deptError: Ref<string>;
  load: () => Promise<void>;
}

export function useAssignDeptOptions(): AssignDeptOptions {
  const userStore = useUserStore();
  const canAssign = computed(() => userStore.has(PERMISSION.TOOL_ASSIGN));
  const deptOptions = ref<{ id: string; name: string }[]>([]);
  const deptError = ref('');

  async function load(): Promise<void> {
    if (!canAssign.value) {
      return;
    }
    deptError.value = '';
    try {
      const list = await listDepts({ status: 1 });
      deptOptions.value = list.map((dept) => ({ id: dept.id, name: dept.name }));
    } catch (cause) {
      deptOptions.value = [];
      deptError.value = cause instanceof Error ? cause.message : '部门列表加载失败';
    }
  }

  onMounted(() => {
    void load();
  });

  return { canAssign, deptOptions, deptError, load };
}
