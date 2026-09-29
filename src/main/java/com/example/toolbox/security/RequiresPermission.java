package com.example.toolbox.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限声明注解。用在 Controller 方法（或类）上，由 PermissionInterceptor 统一校验，
 * 禁止在方法体内手写权限判断（判权单点收口，见 principles.md R3）。
 *
 * <p>语义：
 * <ul>
 *   <li>{@code value} 为单一权限点，要求<strong>必须持有</strong>；</li>
 *   <li>{@code any} 为多权限点数组，要求<strong>至少持有其一</strong>（任一即可）；</li>
 *   <li>两者并存时以 {@code any} 优先（覆盖单值语义）。</li>
 * </ul>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    /** 单一必需权限点（默认空串，与 any 二选一） */
    String value() default "";

    /** 任一即可的权限点集合（覆盖 value 语义） */
    String[] any() default {};
}
