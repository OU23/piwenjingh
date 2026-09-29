package com.example.toolbox.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Set;

/**
 * 当前登录用户上下文对象。密码等敏感信息绝不放入此类。
 * realName / deptName 为展示字段（契约 CurrentUserVO 需要），随令牌一并签发。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private Long userId;
    private String username;
    private String realName;
    private Long deptId;
    private String deptName;
    private Set<String> permissions;
    /** 单账号单登录会话标识（M-18 / 契约 §3.2）；由令牌解析写入，用于每请求校验 */
    private String sid;

    public Set<String> getPermissions() {
        return permissions == null ? Collections.emptySet() : permissions;
    }
}
