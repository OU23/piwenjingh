package com.example.toolbox.common.exception;

import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.result.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：业务异常 → 按 Code.httpStatus 返回统一响应体；
 * 参数校验 / 请求体格式 / 参数类型失败 → 1003；未捕获异常 → 1000（记录日志）。
 * 注意：401/403 主要由 JwtAuthFilter / PermissionInterceptor 直接写回，不在此处产生。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusiness(BusinessException e) {
        Code code = e.getCode();
        return ResponseEntity.status(code.getHttpStatus()).body(R.fail(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.status(Code.PARAM_INVALID.getHttpStatus())
                .body(R.fail(Code.PARAM_INVALID, msg));
    }

    /**
     * 请求体无法解析 → 1003。
     *
     * <p>典型：JSON 语法错、数值字段传了非数字（如 `maxFileSize: "abc"`）、
     * 数组字段传了非数组（如 `ids: "abc"`）。
     * 契约 §9.6 明确「`ids` 为空 / 非数组 → `code = 1003`」，
     * 此前这类输入会落到兜底分支变成 **500 + 1000**，既不符合契约也算"把客户端错误报成服务端故障"（R2）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(Code.PARAM_INVALID.getHttpStatus())
                .body(R.fail(Code.PARAM_INVALID, "请求体格式非法或字段类型不匹配"));
    }

    /** 查询 / 路径参数类型不匹配 → 1003（同样属客户端输入错误，不应报 500） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(Code.PARAM_INVALID.getHttpStatus())
                .body(R.fail(Code.PARAM_INVALID, "参数格式非法：" + e.getName()));
    }

    /** 缺少必填请求参数 → 1003 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(Code.PARAM_INVALID.getHttpStatus())
                .body(R.fail(Code.PARAM_INVALID, "缺少必填参数：" + e.getParameterName()));
    }

    /**
     * 请求体超过服务端接收上限 → 1003（并给出**当前上限**，便于定位）。
     *
     * <p>典型场景：客户端分片大小大于 `spring.servlet.multipart.max-file-size`。
     * 此前该异常会落到兜底分支变成 **500 + 1000**，且因 Tomcat `max-swallow-size` 默认仅 2MB
     * 会直接断开连接，客户端只能看到"网络错误"，**完全无法定位**——
     * 2026-09-20 联调时 5MB 分片被喷成"无法连接服务器"即此故。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(Code.PARAM_INVALID.getHttpStatus())
                .body(R.fail(Code.PARAM_INVALID,
                        "请求体超过服务端接收上限（multipart.max-file-size / max-request-size，默认 1GB）："
                                + "请调小客户端分片大小，或调大服务端上限"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未捕获异常 uri={}", request.getRequestURI(), e);
        return ResponseEntity.status(Code.SYS_ERROR.getHttpStatus()).body(R.fail(Code.SYS_ERROR));
    }
}
