package com.example.toolbox.dept.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 部门 VO（契约 §四）。ID 对外以字符串传输（避免前端精度丢失）。
 */
@Data
@Builder
public class DeptVO {

    private String id;

    private String name;

    private String remark;

    /** 1 启用 / 0 停用 */
    private Integer status;

    /** 部门下用户数 */
    private long userCount;

    private String createTime;
}
