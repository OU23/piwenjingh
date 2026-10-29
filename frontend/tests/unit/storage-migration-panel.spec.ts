/**
 * 测试范围：存量迁移入口（src/components/StorageMigrationPanel.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死迁移入口的交互与**可替换性**（同步完成 / 异步轮询两条分支共用一套界面）
 * 防回归问题：无权限也渲染入口、迁移结果不展示计数与失败明细、非终态不轮询、
 *   轮询在终态后不停、失败静默不提示
 * 关键不变量：
 *   - 无 sys:setting → 区块不渲染且不发请求
 *   - 点「开始迁移」→ 调 startMigration；填了「单次条数」→ 带上 limit
 *   - status=COMPLETED → 直接展示计数；PENDING/RUNNING → 轮询 getMigrationStatus 直到终态
 *   - 失败明细默认收起，展开后可见 versionId + reason
 * 边界说明：不覆盖真实后端；契约 v1.19 §9.9
 * 依赖与限制：接口层与 ElMessage 为桩；Element Plus 以插件注册
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import ElementPlus from 'element-plus';
import StorageMigrationPanel from '@/components/StorageMigrationPanel.vue';
import type { MigrationTaskVO } from '@/api/types';
import { useUserStore } from '@/stores/user';

const mocks = vi.hoisted(() => ({
  info: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  startMigration: vi.fn(),
  getMigrationStatus: vi.fn(),
}));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: { info: mocks.info, error: mocks.error, success: mocks.success, warning: mocks.warning },
  };
});

vi.mock('@/api/tool', () => ({
  startMigration: mocks.startMigration,
  getMigrationStatus: mocks.getMigrationStatus,
}));

function task(overrides: Partial<MigrationTaskVO> = {}): MigrationTaskVO {
  return {
    taskId: 'mig-1',
    status: 'COMPLETED',
    total: 10,
    migrated: 8,
    failed: 1,
    skipped: 1,
    failures: [{ versionId: '3007', reason: '对象存储暂不可用' }],
    startedAt: '2026-09-30 14:20:00',
    finishedAt: '2026-09-30 14:31:12',
    ...overrides,
  };
}

async function mountPanel(permissions: string[]): Promise<VueWrapper> {
  const pinia = createPinia();
  setActivePinia(pinia);
  useUserStore().setLogin({
    token: 'token-value',
    user: {
      userId: '1',
      username: 'admin',
      realName: '管理员',
      deptId: '1',
      deptName: '研发部',
      permissions,
    },
  });

  const wrapper = mount(StorageMigrationPanel, { global: { plugins: [pinia, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text().includes(text));
}

beforeEach(() => {
  mocks.startMigration.mockReset();
  mocks.getMigrationStatus.mockReset();
  mocks.error.mockReset();
  mocks.success.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('存量迁移入口', () => {
  it('无「全局设置」权限：区块不渲染，也不发请求', async () => {
    const wrapper = await mountPanel(['tool:download']);

    expect(wrapper.find('.tb-migrate').exists()).toBe(false);
    expect(buttonByText(wrapper, '开始迁移')).toBeUndefined();
    expect(mocks.startMigration).not.toHaveBeenCalled();
  });

  it('同步完成（本期路径）：展示总数 / 已迁移 / 失败 / 跳过，且不再轮询', async () => {
    mocks.startMigration.mockResolvedValue(task());
    const wrapper = await mountPanel(['sys:setting']);

    expect(wrapper.find('.tb-migrate').exists()).toBe(true);
    await buttonByText(wrapper, '开始迁移')?.trigger('click');
    await flushPromises();

    expect(mocks.startMigration).toHaveBeenCalledTimes(1);
    expect(mocks.startMigration).toHaveBeenCalledWith(undefined);
    expect(mocks.getMigrationStatus).not.toHaveBeenCalled();

    const text = wrapper.text();
    expect(text).toContain('已完成');
    expect(text).toContain('总数：10');
    expect(text).toContain('已迁移：8');
    expect(text).toContain('失败：1');
    expect(text).toContain('跳过：1');
  });

  it('填了单次条数 → 作为 limit 传给后端', async () => {
    mocks.startMigration.mockResolvedValue(task());
    const wrapper = await mountPanel(['sys:setting']);

    // el-input-number 的内部 input 才是真实可输入元素（组件根节点不是 input）
    const limit = wrapper.find('.tb-migrate__form input');
    expect(limit.exists()).toBe(true);
    await limit.setValue('100');
    await buttonByText(wrapper, '开始迁移')?.trigger('click');
    await flushPromises();

    expect(mocks.startMigration).toHaveBeenCalledWith(100);
  });

  it('失败明细默认收起，展开后可见版本与原因', async () => {
    mocks.startMigration.mockResolvedValue(task());
    const wrapper = await mountPanel(['sys:setting']);

    await buttonByText(wrapper, '开始迁移')?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).not.toContain('对象存储暂不可用');

    await buttonByText(wrapper, '展开失败明细')?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('版本 3007');
    expect(wrapper.text()).toContain('对象存储暂不可用');
  });

  it('异步执行器（将来）：非终态即轮询，到终态后停止', async () => {
    vi.useFakeTimers();
    mocks.startMigration.mockResolvedValue(task({ status: 'RUNNING', migrated: 2, finishedAt: null }));
    mocks.getMigrationStatus
      .mockResolvedValueOnce(task({ status: 'RUNNING', migrated: 5, finishedAt: null }))
      .mockResolvedValueOnce(task({ status: 'COMPLETED', migrated: 8 }));

    const wrapper = await mountPanel(['sys:setting']);
    await buttonByText(wrapper, '开始迁移')?.trigger('click');
    await flushPromises();

    expect(mocks.getMigrationStatus).not.toHaveBeenCalled();

    // 第一次轮询：仍在跑
    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();
    expect(mocks.getMigrationStatus).toHaveBeenCalledTimes(1);

    // 第二次轮询：拿到终态 → 停止
    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();
    expect(mocks.getMigrationStatus).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain('已完成');

    await vi.advanceTimersByTimeAsync(6000);
    await flushPromises();
    expect(mocks.getMigrationStatus).toHaveBeenCalledTimes(2);
  });

  it('发起失败：提示后端原因，不展示结果', async () => {
    mocks.startMigration.mockRejectedValue(new Error('对象存储暂不可用'));
    const wrapper = await mountPanel(['sys:setting']);

    await buttonByText(wrapper, '开始迁移')?.trigger('click');
    await flushPromises();

    expect(mocks.error).toHaveBeenCalledWith('对象存储暂不可用');
    expect(wrapper.text()).not.toContain('总数：');
  });
});
