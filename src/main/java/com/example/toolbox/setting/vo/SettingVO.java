package com.example.toolbox.setting.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 全局设置（契约 §十 `SettingVO`）。
 *
 * <p>`storageRoot` **按权限返回**（N-03）：具备 `sys:setting` 权限者得到真实路径，
 * 其他登录用户为 `null` —— 服务端目录结构不需要暴露给普通用户。
 * 因此本 VO **不含任何账号 / 密钥类字段**（验收 A1）。
 */
@Data
@Builder
public class SettingVO {

    /** 存储根路径；无 `sys:setting` 权限时为 null */
    private String storageRoot;

    /** 单文件大小上限（字节） */
    private Long maxFileSize;

    /** 上传分片大小（字节，M-23 / B14）；默认 20 MB。**不含敏感信息**，与 maxFileSize 同款：登录即可读 */
    private Long chunkSize;
}
