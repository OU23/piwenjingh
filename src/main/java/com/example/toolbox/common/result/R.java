package com.example.toolbox.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体（契约 §1.2）。
 * 业务成功与业务失败都用 HTTP 200，靠 code 区分；认证/授权失败由过滤器/拦截器返回真 401/403。
 */
@Data
public class R<T> implements Serializable {

    private int code;
    private String message;
    private T data;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(0);
        r.setMessage("ok");
        r.setData(data);
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static R<Void> fail(Code code) {
        return fail(code, code.getMessage());
    }

    public static R<Void> fail(Code code, String message) {
        R<Void> r = new R<>();
        r.setCode(code.getCode());
        r.setMessage(message == null || message.isBlank() ? code.getMessage() : message);
        return r;
    }
}
