package com.example.toolbox.tool.dto;

import lombok.Data;

import java.util.List;

/**
 * 工具侧保存授权入参（契约 §9.8 ToolGrantUsersDTO）。
 * userIds 为<b>全量列表</b>（覆盖式保存）；传空数组 = 清空全部授权。
 */
@Data
public class ToolGrantUsersDTO {

    /** 用户 ID 全量列表（覆盖式保存）；缺失 → 1003；非数组 → 绑定失败 */
    private List<String> userIds;
}
