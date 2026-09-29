package com.example.toolbox.common.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 应用日志留痕（principles.md 原则 10 + 豁免 S-03 的 MVP 落地口径）。
 *
 * <p>S-03 批准：MVP **不建审计日志表、不做查询界面**，改为用**应用日志**记录
 * "关键写操作 + 登录成功 / 失败 / 退出"。检查该条时按"应用日志是否覆盖上述事件"判定。
 *
 * <p>字段口径（原则 10）：谁、何时、对什么数据、做了什么、结果。
 * **禁止**记录明文密码、令牌、密钥（security.md）。
 */
@Slf4j
@Component
public class AuditLogger {

    /** 登录成功 */
    public void loginSuccess(String username) {
        log.info("[AUDIT] action=LOGIN result=SUCCESS username={}", username);
    }

    /** 登录失败（不区分账号不存在 / 密码错 / 停用，避免账号探测） */
    public void loginFail(String username) {
        log.warn("[AUDIT] action=LOGIN result=FAIL username={}", username);
    }

    /** 登出 */
    public void logout(String username) {
        log.info("[AUDIT] action=LOGOUT username={}", username);
    }

    /**
     * 关键写操作（新增 / 修改 / 删除）。
     *
     * @param operator 操作人账号
     * @param action   动作，如 USER_CREATE / DEPT_STATUS
     * @param target   目标标识，如 "user:12"
     * @param result   结果，OK / FAIL
     */
    public void write(String operator, String action, String target, String result) {
        log.info("[AUDIT] action={} operator={} target={} result={}", action, operator, target, result);
    }
}
