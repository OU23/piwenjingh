/**
 * 测试范围：Excel 导入对话框（src/components/ImportDialog.vue）
 * 测试类型：组件（jsdom）
 * 测试目的：锁死 M-19 / P8 的关键行为（plan P8 §T2、acceptance A3 ~ A10 / A12）
 * 防回归问题：非 .xlsx / 超大文件仍发请求；把「跳过」渲染成失败色（或反之）；
 *   无失败仍显示错误下载入口；提交中可重复点击；导入失败却关掉弹窗；
 *   请求失败后对话框被关闭、换文件仍提交旧文件
 * 关键不变量：
 *   - 预校验不通过 → **不发请求**（只就地提示）
 *   - 「跳过」中性色 / 「失败」错误色，**两者不得混用**
 *   - `failCount = 0` → **不渲染**错误明细下载入口
 *   - 提交中按钮 disabled，重复点击**只发一次**
 *   - 请求失败 → 就地提示且**弹窗不关闭**
 *   - 关闭时**有成功行**才 emit `imported`（全跳过 / 全失败不触发刷新）
 * 边界说明：不覆盖真实后端与 Excel 解析（前端不参与解析）
 * 依赖与限制：`saveBlob` 与 `ElMessage` 为桩；Element Plus 以插件注册
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import type { ImportResultVO } from '@/api/types';
import { XLSX_MIME } from '@/utils/base64';
import { IMPORT_FILE_MAX_SIZE } from '@/utils/fileValidation';
import ImportDialog from '@/components/ImportDialog.vue';

const mocks = vi.hoisted(() => ({
  saveBlob: vi.fn(),
  error: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  saveBlob: mocks.saveBlob,
}));

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>();
  return {
    ...actual,
    ElMessage: { success: vi.fn(), error: mocks.error, info: vi.fn(), warning: vi.fn() },
  };
});

function makeResult(partial: Partial<ImportResultVO> = {}): ImportResultVO {
  return {
    total: 0,
    successCount: 0,
    skipCount: 0,
    failCount: 0,
    createdDeptNames: [],
    errors: [],
    errorFileBase64: null,
    errorFileName: null,
    ...partial,
  };
}

/** 伪造一个指定大小的文件（jsdom 的 `File.size` 只读） */
function makeFile(name: string, size = 1024): File {
  const file = new File(['x'], name, { type: XLSX_MIME });
  Object.defineProperty(file, 'size', { value: size, configurable: true });
  return file;
}

let submit: ReturnType<typeof vi.fn>;
let downloadTemplate: ReturnType<typeof vi.fn>;

async function mountDialog(modelValue = true): Promise<VueWrapper> {
  const wrapper = mount(ImportDialog, {
    props: {
      modelValue,
      title: 'Excel 导入 · 用户',
      templateName: '用户导入模板.xlsx',
      downloadTemplate: downloadTemplate as unknown as () => Promise<void>,
      submit: submit as unknown as (file: File) => Promise<ImportResultVO>,
    },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  return wrapper;
}

/** 走真实的 `<input type="file">` 选择路径 */
async function pickFile(wrapper: VueWrapper, file: File): Promise<void> {
  const input = wrapper.find('input[type="file"]');
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
  await flushPromises();
}

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text().includes(text));
}

async function startImport(wrapper: VueWrapper): Promise<void> {
  await buttonByText(wrapper, '开始导入')?.trigger('click');
  await flushPromises();
}

/**
 * 触发弹窗关闭。
 * 真实环境里 `el-dialog` 的 `close` 由**关闭动画结束**触发；jsdom 下过渡被 stub，不会自动触发，
 * 故这里直接派发该事件 —— 测的是「关闭时按条件 emit `imported`」这条逻辑本身。
 */
async function closeDialog(wrapper: VueWrapper): Promise<void> {
  wrapper.findComponent({ name: 'ElDialog' }).vm.$emit('close');
  await flushPromises();
}

beforeEach(() => {
  mocks.saveBlob.mockReset();
  mocks.error.mockReset();
  submit = vi.fn().mockResolvedValue(makeResult());
  downloadTemplate = vi.fn().mockResolvedValue(undefined);
});

describe('Excel 导入对话框 · 文件预校验', () => {
  it('非 .xlsx → 就地拦截且**不发请求**（A3）', async () => {
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.csv'));

    expect(wrapper.text()).toContain('仅支持 .xlsx 文件');
    await startImport(wrapper);
    expect(submit).not.toHaveBeenCalled();
  });

  it('超过 10 MB → 就地拦截且**不发请求**（A4）', async () => {
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx', IMPORT_FILE_MAX_SIZE + 1));

    expect(wrapper.text()).toContain('超过上限');
    await startImport(wrapper);
    expect(submit).not.toHaveBeenCalled();
  });

  it('合法 .xlsx → 提交的就是所选文件（C9 换文件只提交最后一个）', async () => {
    const wrapper = await mountDialog();

    await pickFile(wrapper, makeFile('先选的.xlsx'));
    await pickFile(wrapper, makeFile('后选的.xlsx'));
    expect(wrapper.text()).toContain('后选的.xlsx');

    await startImport(wrapper);
    expect(submit).toHaveBeenCalledTimes(1);
    expect((submit.mock.calls[0]?.[0] as File).name).toBe('后选的.xlsx');
  });

  it('非法文件被拦后，「开始导入」不可点（不会带旧文件误提交）', async () => {
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('正常.xlsx'));
    expect(buttonByText(wrapper, '开始导入')?.attributes('disabled')).toBeUndefined();

    await pickFile(wrapper, makeFile('坏文件.xls'));
    expect(buttonByText(wrapper, '开始导入')?.attributes('disabled')).toBeDefined();
  });
});

describe('Excel 导入对话框 · 结果区', () => {
  it('四类计数渲染，且「跳过」与「失败」样式类不同（A5）', async () => {
    submit.mockResolvedValue(makeResult({ total: 50, successCount: 45, skipCount: 3, failCount: 2 }));
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx'));
    await startImport(wrapper);

    const text = wrapper.text();
    expect(text).toContain('共 50 行');
    expect(text).toContain('成功 45');
    expect(text).toContain('跳过 3');
    expect(text).toContain('失败 2');

    const skip = wrapper.find('.tb-import__skip');
    const fail = wrapper.find('.tb-import__fail');
    expect(skip.exists()).toBe(true);
    expect(fail.exists()).toBe(true);
    // 中性色与错误色**不得混用**（契约 §6.2）
    expect(skip.classes()).not.toEqual(fail.classes());
  });

  it('failCount = 0 → **不渲染**错误明细下载入口（A6）', async () => {
    submit.mockResolvedValue(makeResult({ total: 3, successCount: 1, skipCount: 2, failCount: 0 }));
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx'));
    await startImport(wrapper);

    expect(wrapper.text()).toContain('跳过 2');
    expect(buttonByText(wrapper, '下载错误明细')).toBeUndefined();
  });

  it('failCount > 0 → 可下载；点击后由 Base64 生成 Blob 并以 errorFileName 保存（A7）', async () => {
    submit.mockResolvedValue(
      makeResult({
        total: 2,
        successCount: 0,
        failCount: 2,
        errors: [
          { rowNum: 3, message: '账号格式不正确' },
          { rowNum: 5, message: '部门不存在' },
        ],
        errorFileBase64: btoa('fake-xlsx-bytes'),
        errorFileName: '用户导入错误明细_20260924.xlsx',
      }),
    );

    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx'));
    await startImport(wrapper);

    expect(wrapper.text()).toContain('第 3 行：账号格式不正确');
    expect(wrapper.text()).toContain('第 5 行：部门不存在');

    await buttonByText(wrapper, '下载错误明细')?.trigger('click');
    await flushPromises();

    expect(mocks.saveBlob).toHaveBeenCalledTimes(1);
    const [blob, fileName] = mocks.saveBlob.mock.calls[0] as [Blob, string];
    expect(blob).toBeInstanceOf(Blob);
    expect(blob.size).toBeGreaterThan(0);
    expect(fileName).toBe('用户导入错误明细_20260924.xlsx');
  });

  it('createdDeptNames 非空 → 醒目提示并列出部门名；为空 → 不渲染（A8）', async () => {
    submit.mockResolvedValue(makeResult({ total: 2, successCount: 2, createdDeptNames: ['新零售部', '渠道部'] }));
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx'));
    await startImport(wrapper);

    const created = wrapper.find('.tb-import__created');
    expect(created.exists()).toBe(true);
    expect(created.text()).toContain('自动新建了');
    expect(created.text()).toContain('新零售部');
    expect(created.text()).toContain('渠道部');
  });

  it('无自动建部门 → 不渲染该提示块（A8 反向）', async () => {
    submit.mockResolvedValue(makeResult({ total: 1, successCount: 1 }));
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx'));
    await startImport(wrapper);

    expect(wrapper.find('.tb-import__created').exists()).toBe(false);
  });
});

describe('Excel 导入对话框 · 提交与失败', () => {
  it('提交中按钮 disabled，重复点击**只发一次**（A9）', async () => {
    let release: (value: ImportResultVO) => void = () => {};
    submit.mockImplementation(
      () =>
        new Promise<ImportResultVO>((resolve) => {
          release = resolve;
        }),
    );

    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx'));

    const startButton = buttonByText(wrapper, '开始导入');
    await startButton?.trigger('click');
    await flushPromises();

    expect(startButton?.attributes('disabled')).toBeDefined();
    // 提交中再点一次：不产生第二次请求
    await buttonByText(wrapper, '开始导入')?.trigger('click');
    await flushPromises();
    expect(submit).toHaveBeenCalledTimes(1);

    release(makeResult({ total: 1, successCount: 1 }));
    await flushPromises();
  });

  it('请求失败 → 就地提示且**弹窗不关闭**（A12 / C5）', async () => {
    submit.mockRejectedValue(new Error('文件内容无法解析'));
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx'));
    await startImport(wrapper);

    expect(wrapper.text()).toContain('文件内容无法解析');
    // 弹窗未关闭（`modelValue` 仍为 true）
    expect((wrapper.props() as { modelValue: boolean }).modelValue).toBe(true);
    // 可重试：按钮回到可用状态
    expect(buttonByText(wrapper, '开始导入')?.attributes('disabled')).toBeUndefined();
  });

  it('关闭且有成功行 → emit imported（A10）', async () => {
    submit.mockResolvedValue(makeResult({ total: 2, successCount: 2 }));
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx'));
    await startImport(wrapper);

    await closeDialog(wrapper);

    expect(wrapper.emitted('imported')).toHaveLength(1);
  });

  it('全跳过 / 全失败 → 不触发 imported（数据没变，无需刷新）', async () => {
    submit.mockResolvedValue(makeResult({ total: 2, successCount: 0, skipCount: 2, failCount: 0 }));
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx'));
    await startImport(wrapper);

    await closeDialog(wrapper);

    expect(wrapper.emitted('imported')).toBeUndefined();
  });
});

describe('Excel 导入对话框 · 模板与重置', () => {
  it('对话框内可下载模板（调用父页注入的下载函数）', async () => {
    const wrapper = await mountDialog();
    await buttonByText(wrapper, '下载模板')?.trigger('click');
    await flushPromises();

    expect(downloadTemplate).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain('用户导入模板.xlsx');
  });

  it('模板下载失败 → 就地提示（不抛出未捕获异常）', async () => {
    downloadTemplate.mockRejectedValue(new Error('模板暂时不可用'));
    const wrapper = await mountDialog();
    await buttonByText(wrapper, '下载模板')?.trigger('click');
    await flushPromises();

    expect(mocks.error).toHaveBeenCalledWith('模板暂时不可用');
  });

  it('再次打开是干净状态：不残留上一轮结果（C10）', async () => {
    submit.mockResolvedValue(makeResult({ total: 5, successCount: 5 }));
    const wrapper = await mountDialog();
    await pickFile(wrapper, makeFile('用户.xlsx'));
    await startImport(wrapper);
    expect(wrapper.text()).toContain('成功 5');

    await wrapper.setProps({ modelValue: false });
    await flushPromises();
    await wrapper.setProps({ modelValue: true });
    await flushPromises();

    expect(wrapper.text()).not.toContain('成功 5');
    expect(wrapper.text()).toContain('未选择文件');
  });
});
