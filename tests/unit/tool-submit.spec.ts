/**
 * 测试范围：上传后的落库提交（src/api/toolSubmit.ts）——同名分支与字段映射
 * 测试类型：单元（接口模块为桩）
 * 测试目的：锁死"名称全局唯一、命中同名一律阻止提交（name-blocked）"这一条
 *          （契约 §9.4；临时偏离 N-01：关闭"同名转版本"静默追加，待版本管理恢复）
 * 防回归问题：命中同名却仍然新建工具 / 静默追加为版本、新建时漏传 fileKey、版本模式误调新建接口
 * 关键不变量：
 *   - 命中同名（existed = true，无论 canAppend）→ 返回 name-blocked，不调新建、不调追加
 *   - 未命中 → 调新建接口，且 DTO 字段与契约一致
 *   - 追加版本模式必须带 toolId，缺失即报错
 * 边界说明：不覆盖真实接口与后端唯一性约束（由 B3 负责）
 * 依赖与限制：`@/api/tool` 整体为桩
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { submitTool } from '@/api/toolSubmit';
import type { ToolMetaValues } from '@/utils/toolMetaValidation';

const mocks = vi.hoisted(() => ({
  checkToolName: vi.fn(),
  createTool: vi.fn(),
  createToolVersion: vi.fn(),
}));

vi.mock('@/api/tool', () => ({
  checkToolName: mocks.checkToolName,
  createTool: mocks.createTool,
  createToolVersion: mocks.createToolVersion,
}));

const VALUES: ToolMetaValues = {
  name: '数据清洗小工具',
  version: 'v1.0',
  intro: '批量清洗 CSV',
  readme: '第一步：解压',
  fileExt: 'ZIP',
  platform: 'Windows',
  dependency: '',
  tagIds: ['1', '2'],
  scope: 'DEPT',
};

const UPLOAD = { fileKey: 'file-key-1', fileName: 'demo.zip', fileSize: 2048 };

beforeEach(() => {
  mocks.checkToolName.mockReset();
  mocks.createTool.mockReset();
  mocks.createToolVersion.mockReset();
});

describe('上传后的落库提交', () => {
  it('未命中同名：新建工具，字段按契约映射', async () => {
    mocks.checkToolName.mockResolvedValue({ existed: false, toolId: null, latestVersion: null, canAppend: false });
    mocks.createTool.mockResolvedValue({ toolId: '1001', versionId: '2001' });

    const outcome = await submitTool({ mode: 'create', values: VALUES, upload: UPLOAD });

    expect(mocks.checkToolName).toHaveBeenCalledWith({ name: '数据清洗小工具' });
    expect(mocks.createTool).toHaveBeenCalledTimes(1);
    expect(mocks.createTool).toHaveBeenCalledWith({
      name: '数据清洗小工具',
      version: 'v1.0',
      intro: '批量清洗 CSV',
      readme: '第一步：解压',
      fileExt: 'zip', // 统一小写
      platform: 'Windows',
      dependency: undefined, // 空值不下发
      tagIds: ['1', '2'],
      scope: 'DEPT',
      fileKey: 'file-key-1',
      fileName: 'demo.zip',
      fileSize: 2048,
    });
    expect(mocks.createToolVersion).not.toHaveBeenCalled();
    expect(outcome).toEqual({ kind: 'created', toolId: '1001', versionId: '2001' });
  });

  /**
   * **v1.21 行为变更**：P18 的"命中同名一律阻止"是临时口径，已随版本管理恢复终止。
   * 现在同上传人命中未删同名 → **直接追加为新版本**（名称锁定、版本号由用户填）。
   */
  it('命中同名（existed = true）：恢复"同名转版本"，直接追加为新版本', async () => {
    mocks.checkToolName.mockResolvedValue({
      existed: true,
      toolId: '1001',
      latestVersion: 'v1.2',
      canAppend: true,
      inTrash: false,
      trashToolId: null,
    });
    mocks.createToolVersion.mockResolvedValue('2002');

    const outcome = await submitTool({ mode: 'create', values: VALUES, upload: UPLOAD });

    expect(mocks.createTool).not.toHaveBeenCalled();
    expect(mocks.createToolVersion).toHaveBeenCalledTimes(1);
    expect(mocks.createToolVersion).toHaveBeenCalledWith(
      '1001',
      expect.objectContaining({ version: 'v1.0', fileKey: 'file-key-1', fileSize: 2048 }),
    );
    expect(outcome).toEqual({ kind: 'version', toolId: '1001', versionId: '2002' });
  });

  /** **v1.21 新增分支**：回收站已有同上传人同名工具 → 不建不追加，交由页面二选一 */
  it('回收站已有同名（inTrash = true）：返回 in-trash，不新建也不追加', async () => {
    mocks.checkToolName.mockResolvedValue({
      existed: false,
      toolId: null,
      latestVersion: null,
      canAppend: false,
      inTrash: true,
      trashToolId: '9001',
    });

    const outcome = await submitTool({ mode: 'create', values: VALUES, upload: UPLOAD });

    expect(outcome).toEqual({ kind: 'in-trash', toolId: '9001', latestVersion: null });
    expect(mocks.createTool).not.toHaveBeenCalled();
    expect(mocks.createToolVersion).not.toHaveBeenCalled();
  });

  it('从工具详情进入（追加版本模式）：直接追加，不做同名检查', async () => {
    mocks.createToolVersion.mockResolvedValue('2003');

    const outcome = await submitTool({ mode: 'version', values: VALUES, upload: UPLOAD, toolId: '1009' });

    expect(mocks.checkToolName).not.toHaveBeenCalled();
    expect(mocks.createToolVersion).toHaveBeenCalledWith('1009', expect.objectContaining({ version: 'v1.0' }));
    expect(outcome).toEqual({
      kind: 'version',
      toolId: '1009',
      versionId: '2003',
    });
  });

  it('追加版本时格式为空：从文件名推导', async () => {
    mocks.createToolVersion.mockResolvedValue('2004');

    await submitTool({
      mode: 'version',
      values: { ...VALUES, fileExt: '' },
      upload: { fileKey: 'k', fileName: 'package.tar.gz', fileSize: 10 },
      toolId: '1009',
    });

    expect(mocks.createToolVersion).toHaveBeenCalledWith('1009', expect.objectContaining({ fileExt: 'gz' }));
  });

  it('追加版本模式缺少 toolId：直接报错，不发起请求', async () => {
    await expect(submitTool({ mode: 'version', values: VALUES, upload: UPLOAD })).rejects.toThrow('缺少目标工具');
    expect(mocks.createToolVersion).not.toHaveBeenCalled();
  });
});
