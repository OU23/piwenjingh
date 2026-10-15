package com.example.toolbox.setting.dto;

import lombok.Data;

/**
 * 保存全局设置（契约 §十 `SettingSaveDTO`）。
 *
 * <p>三项（`storageRoot` / `maxFileSize` / `chunkSize`）均为**选填，传了才改**（支持只提交变更项）；
 * **至少传一项** —— 都不传视为无效请求
 * （验收 C6），由服务层判 1003，而不是静默成功。
 *
 * <p>刻意**不用** `@NotNull`/`@Positive` 注解做必填与正数校验：注解只能表达"单字段"规则，
 * 而"至少一项"是跨字段规则；正数校验放服务层可与"非数字"（Jackson 解析失败 → 1003）
 * 走同一条错误路径，避免出现 400 与 1003 两种表现。
 */
@Data
public class SettingSaveDTO {

    /** 存储根路径（绝对路径，统一正斜杠）；不传表示不修改 */
    private String storageRoot;

    /** 单文件大小上限（字节）；不传表示不修改 */
    private Long maxFileSize;

    /** 上传分片大小（字节，M-23 / B14）；不传表示不修改。区间 5 ~ 100 MB，越界 → 1003 */
    private Long chunkSize;
}
