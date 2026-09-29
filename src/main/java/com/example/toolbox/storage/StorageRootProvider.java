package com.example.toolbox.storage;

/**
 * 存储根路径提供者（principles.md R1 存储层隔离 + R3 单一读取入口）。
 *
 * <p><b>为什么需要这个接口</b>：根路径有两个来源 —— 全局设置表（管理员在设置页改，B4 起）
 * 与配置项（`toolbox.storage.root`，部署兜底）。若存储实现直接读配置项，
 * 就会出现"设置改了但文件仍落在旧目录"的假生效。
 *
 * <p>因此把"当前生效的根路径"抽象为接口：<b>存储实现只依赖本接口，不关心来源</b>；
 * 取值优先级由上层实现决定（见 `setting` 模块的实现类）。
 * 这样既修好了"改设置即生效"，又不让存储层反向依赖业务模块 —— 依赖方向仍是
 * `setting → storage`，存储层保持可替换（R1）。
 */
public interface StorageRootProvider {

    /**
     * 当前生效的存储根路径。
     *
     * @return 归一化后的绝对路径；**未配置时返回 null 或空串**（由存储实现给出明确错误码，不抛 NPE）
     */
    String currentRoot();
}
