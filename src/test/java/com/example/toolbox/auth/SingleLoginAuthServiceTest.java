package com.example.toolbox.auth;

import com.example.toolbox.auth.service.AuthService;
import com.example.toolbox.auth.vo.LoginVO;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单账号单登录 —— 登录分支（M-18）。
 * 覆盖验收 A1 / A3 / A4 / C1 / C2 / C4，以及「force 不得绕过密码校验」约束。
 */
class SingleLoginAuthServiceTest extends IntegrationTestBase {

    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private DeptService deptService;
    @Autowired
    private JwtProvider jwtProvider;

    private Long deptId;

    @BeforeEach
    void setUpData() {
        loginAs(999_999L, "admin", 1L, PermissionCode.USER_MANAGE);
        DeptSaveDTO d = new DeptSaveDTO();
        d.setName("研发部");
        deptId = Long.valueOf(deptService.create(d));
    }

    private Long createUser(String username, String password) {
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername(username);
        dto.setRealName("测试用户");
        dto.setPassword(password);
        dto.setDeptId(String.valueOf(deptId));
        dto.setPermissions(List.of(PermissionCode.TOOL_DOWNLOAD));
        return Long.valueOf(userService.create(dto));
    }

    private String sidOf(String token) {
        String sid = jwtProvider.parse(token).getSid();
        assertNotNull(sid, "令牌应内嵌会话标识 sid");
        return sid;
    }

    /** C1：首次登录（无活跃会话）不返回 1008，直接成功 */
    @Test
    void firstLoginSucceedsWithoutForce() {
        Long uid = createUser("u1", "pass123456");

        LoginVO vo = authService.login("u1", "pass123456", false);

        assertNotNull(vo.getToken());
        sidOf(vo.getToken());
        assertTrue(sessionRegistry.hasActive(uid), "登录成功应建立会话");
    }

    /** A3：已有活跃会话且 force 未传 / false → 1008（HTTP 200 业务码），不签发令牌 */
    @Test
    void secondLoginWithoutForceReturns1008() {
        createUser("u1", "pass123456");
        authService.login("u1", "pass123456", false);

        BusinessException nullForce = assertThrows(BusinessException.class,
                () -> authService.login("u1", "pass123456", null));
        assertEquals(Code.FORCE_LOGIN_REQUIRED, nullForce.getCode(), "force 未传应返回 1008");
        assertEquals(1008, nullForce.getCode().getCode());
        assertEquals(200, nullForce.getCode().getHttpStatus(), "1008 必须是 HTTP 200 业务失败");

        BusinessException falseForce = assertThrows(BusinessException.class,
                () -> authService.login("u1", "pass123456", false));
        assertEquals(Code.FORCE_LOGIN_REQUIRED, falseForce.getCode(), "force=false 应返回 1008");
    }

    /** A1 + A4：force=true → 新会话生效，旧会话立即失效 */
    @Test
    void forceLoginReplacesSessionAndInvalidatesOld() {
        Long uid = createUser("u1", "pass123456");
        String sid1 = sidOf(authService.login("u1", "pass123456", false).getToken());
        String sid2 = sidOf(authService.login("u1", "pass123456", true).getToken());

        assertNotEquals(sid1, sid2, "force 登录必须换新会话");
        assertFalse(sessionRegistry.isCurrent(uid, sid1), "旧会话应立即失效");
        assertTrue(sessionRegistry.isCurrent(uid, sid2), "新会话应生效");
    }

    /** C2：连续两次 force=true 均成功，最终只有最后一个会话有效 */
    @Test
    void consecutiveForceLoginsBothSucceed() {
        Long uid = createUser("u1", "pass123456");
        String s1 = sidOf(authService.login("u1", "pass123456", true).getToken());
        String s2 = sidOf(authService.login("u1", "pass123456", true).getToken());

        assertNotEquals(s1, s2);
        assertFalse(sessionRegistry.isCurrent(uid, s1));
        assertTrue(sessionRegistry.isCurrent(uid, s2), "最终会话应为最后一次登录");
    }

    /** C4：会话消失后重新登录 → 建立新会话，旧 sid 仍不可用 */
    @Test
    void reloginAfterSessionLostEstablishesNewSession() {
        Long uid = createUser("u1", "pass123456");
        String old = sidOf(authService.login("u1", "pass123456", false).getToken());

        sessionRegistry.remove(uid);   // 模拟登出 / 停用导致会话消失
        String fresh = sidOf(authService.login("u1", "pass123456", false).getToken());

        assertNotEquals(old, fresh);
        assertFalse(sessionRegistry.isCurrent(uid, old), "旧 sid 不得复活");
        assertTrue(sessionRegistry.isCurrent(uid, fresh));
    }

    /** 约束：force 不得绕过密码校验 —— 密码错仍 1006，且不得替换现有会话 */
    @Test
    void forceDoesNotBypassPasswordCheck() {
        Long uid = createUser("u1", "pass123456");
        String before = sidOf(authService.login("u1", "pass123456", false).getToken());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login("u1", "wrong-password", true));
        assertEquals(Code.BAD_CREDENTIALS, ex.getCode(), "密码错必须仍为 1006");
        assertTrue(sessionRegistry.isCurrent(uid, before), "密码错不得替换现有会话");
    }

    /** A11：登录响应 expiresIn 由令牌配置计算（生产为 12h = 43200，见 application.yml） */
    @Test
    void loginResponseExpiresInMatchesConfiguredTtl() {
        createUser("u1", "pass123456");

        LoginVO vo = authService.login("u1", "pass123456", false);

        assertEquals(jwtProvider.getExpiresInSeconds(), vo.getExpiresIn(),
                "expiresIn 应取自 JWT 配置口径，不得硬编码");
        assertTrue(vo.getExpiresIn() > 0);
    }
}
