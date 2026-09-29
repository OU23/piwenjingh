package com.example.toolbox.tool.dto;

import lombok.Data;

import java.util.List;

/**
 * 用户侧保存授权入参（契约 §6.1 UserToolGrantDTO）。
 * toolIds 为<b>全量列表</b>（覆盖式保存）；传空数组 = 清空全部授权。
 */
@Data
public class UserToolGrantDTO {

    /** 工具 ID 全量列表（覆盖式保存）；缺失 → 1003；非数组 → 绑定失败 */
    private List<String> toolIds;
}
