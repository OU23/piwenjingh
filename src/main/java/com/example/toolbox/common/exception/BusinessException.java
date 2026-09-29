package com.example.toolbox.common.exception;

import com.example.toolbox.common.result.Code;
import lombok.Getter;

/**
 * 业务异常：携带契约错误码。
 * 由全局异常处理器统一转换为统一响应体，HTTP 状态取 Code.httpStatus（业务码统一 200，1001/1002/1000 例外）。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Code code;

    public BusinessException(Code code) {
        super(code.getMessage());
        this.code = code;
    }

    public BusinessException(Code code, String message) {
        super(message);
        this.code = code;
    }
}
