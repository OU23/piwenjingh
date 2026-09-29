package com.example.toolbox.security;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 授权拦截器：对标注 @RequiresPermission 的接口做权限校验。
 * 已登录但权限点不足 → 抛出 BusinessException(FORBIDDEN) → 全局处理器回真 403。
 * 与 JwtAuthFilter（认证，回 401）分工明确。
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private final PermissionChecker permissionChecker;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresPermission annotation = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (annotation == null) {
            annotation = AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), RequiresPermission.class);
        }
        if (annotation == null) {
            return true;
        }
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        // 判定规则收口于 PermissionChecker（R3），本拦截器不自行写判定
        if (annotation.any().length > 0) {
            permissionChecker.requireAny(user, annotation.any());
        } else {
            permissionChecker.require(user, annotation.value());
        }
        return true;
    }
}
