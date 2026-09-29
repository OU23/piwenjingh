package com.example.toolbox.security;

/**
 * 当前登录用户上下文（请求级）。由 JwtAuthFilter 在请求开始时写入，结束后清除。
 * 业务层取归属部门、判权一律从此处取，绝不使用前端传入的部门/用户 ID。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
