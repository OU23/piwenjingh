package com.example.toolbox.auth;

import com.example.toolbox.auth.service.AuthService;
import com.example.toolbox.auth.vo.CurrentUserVO;
import com.example.toolbox.auth.vo.LoginVO;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.DateTimeUtils;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证模块（验收 A1 / A8）+ 通用工具类。
 * 覆盖：登录成功签发令牌、令牌可解析回登录上下文、三种失败统一 1006、
 * 停用后登录失效、当前用户信息完整、登录信息不含敏感字段。
 */
class AuthServiceTest extends IntegrationTestBase {

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
        // 操作人 ID 用极大值，避免与 H2 自增主键（从 1 开始）碰撞而误触"不能停用自身"守卫
        loginAs(999_999L, "admin", 1L, PermissionCode.USER_MANAGE, PermissionCode.DEPT_MANAGE);
        DeptSaveDTO d = new DeptSaveDTO();
        d.setName("研发部");
        deptId = Long.valueOf(deptService.create(d));
    }

    private String createUser(String username, String password, String... perms) {
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername(username);
        dto.setRealName("测试用户");
        dto.setPassword(password);
        dto.setDeptId(String.valueOf(deptId));
        dto.setPermissions(perms.length == 0 ? null : List.of(perms));
        return userService.create(dto);
    }

    /** A1：登录成功，令牌可解析回登录上下文（含权限与部门） */
    @Test
    void loginIssuesParseableTokenWithContext() {
        createUser("zhangsan", "pass123456", PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD);

        LoginVO login = authService.login("zhangsan", "pass123456", false);
        assertNotNull(login.getToken());
        assertTrue(login.getExpiresIn() > 0, "应返回正数有效期");

        LoginUser parsed = jwtProvider.parse(login.getToken());
        assertEquals("zhangsan", parsed.getUsername());
        assertEquals(deptId, parsed.getDeptId());
        assertEquals(java.util.Set.of(PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD),
                parsed.getPermissions(), "令牌应携带权限点");
        assertNotNull(parsed.getRealName(), "令牌应携带展示用姓名");
        assertNotNull(parsed.getDeptName(), "令牌应携带展示用部门名");
    }

    /** 当前用户信息完整，且**不含**密码 / 令牌等敏感字段 */
    @Test
    void currentUserIsCompleteAndFreeOfSecrets() {
        createUser("zhangsan", "pass123456", PermissionCode.TOOL_UPLOAD);
        LoginUser ctx = jwtProvider.parse(authService.login("zhangsan", "pass123456", false).getToken());

        CurrentUserVO me = authService.currentUser(ctx);
        assertEquals("zhangsan", me.getUsername());
        assertEquals("测试用户", me.getRealName());
        assertEquals(String.valueOf(deptId), me.getDeptId());
        assertEquals("研发部", me.getDeptName());
        assertEquals(List.of(PermissionCode.TOOL_UPLOAD), me.getPermissions());

        // 结构上不应存在 password / token 字段
        List<String> fields = java.util.Arrays.stream(CurrentUserVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList();
        assertTrue(fields.stream().noneMatch(f -> f.toLowerCase().contains("password")),
                "CurrentUserVO 不得含 password 字段，实际：" + fields);
        assertTrue(fields.stream().noneMatch(f -> f.toLowerCase().contains("token")),
                "CurrentUserVO 不得含 token 字段，实际：" + fields);
    }

    /** 未登录（上下文为空）取当前用户 → 1001 */
    @Test
    void currentUserWithoutContextIsUnauthenticated() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.currentUser(null));
        assertEquals(Code.UNAUTHENTICATED, ex.getCode(), "未登录应返回 1001");
        assertEquals(401, Code.UNAUTHENTICATED.getHttpStatus(), "1001 必须映射真 401");
    }

    /** 三种失败统一 1006：账号不存在 / 密码错 / 账号停用 —— 不区分，避免账号探测 */
    @Test
    void allThreeFailureModesReturnSameCode() {
        String id = createUser("zhangsan", "pass123456");
        Long uid = Long.valueOf(id);

        BusinessException notFound = assertThrows(BusinessException.class,
                () -> authService.login("nobody", "pass123456", false));
        BusinessException wrongPwd = assertThrows(BusinessException.class,
                () -> authService.login("zhangsan", "wrong-password", false));
        assertEquals(Code.BAD_CREDENTIALS, notFound.getCode(), "账号不存在 → 1006");
        assertEquals(Code.BAD_CREDENTIALS, wrongPwd.getCode(), "密码错 → 1006");
        assertEquals(notFound.getMessage(), wrongPwd.getMessage(),
                "两种失败提示必须完全一致（防账号探测）");

        // 停用后再登录
        com.example.toolbox.user.dto.UserStatusDTO disable =
                new com.example.toolbox.user.dto.UserStatusDTO();
        disable.setStatus(0);
        userService.updateStatus(uid, disable);

        BusinessException disabled = assertThrows(BusinessException.class,
                () -> authService.login("zhangsan", "pass123456", false));
        assertEquals(Code.BAD_CREDENTIALS, disabled.getCode(), "账号停用 → 1006");
        assertEquals(wrongPwd.getMessage(), disabled.getMessage(),
                "停用的提示也须与密码错一致（不暴露停用状态）");
    }

    /** 1006 必须是业务失败：HTTP 200（不是 401，否则前端会误判为登录失效） */
    @Test
    void badCredentialsIsBusinessFailureNot401() {
        assertEquals(200, Code.BAD_CREDENTIALS.getHttpStatus(),
                "1006 应为 HTTP 200 + 业务码（真 401 只留给 1001）");
    }

    /** A8 前置：权限点实时生效，令牌内权限不被长期固化 */
    @Test
    void permissionsAreReadFreshFromDatabaseOnLogin() {
        String id = createUser("zhangsan", "pass123456");
        Long uid = Long.valueOf(id);
        // v1.14：create 默认 tool:download；本用例显式清空以保留「初始无权限」基线
        // （默认权限行为由 UserDefaultPermissionTest A6/A7/A8 专门覆盖）
        userService.savePermissions(uid, List.of());

        assertEquals(0, authService.login("zhangsan", "pass123456", false)
                .getUser().getPermissions().size(), "初始无权限");

        userService.savePermissions(uid, List.of(PermissionCode.DEPT_MANAGE));
        // 同一账号已在上一步建立会话：重新登录须显式 force（M-18 契约行为）
        assertTrue(authService.login("zhangsan", "pass123456", true)
                        .getUser().getPermissions().contains(PermissionCode.DEPT_MANAGE),
                "授权后重新登录应带出权限");
    }

    /** 工具类：ID 解析对非法格式返回 1003，正常 ID 往返一致 */
    @Test
    void idUtilsParsesAndRejects() {
        assertEquals(42L, IdUtils.parse("42", "用户ID"));
        assertEquals("42", IdUtils.toStr(42L));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> IdUtils.parse("abc", "用户ID"));
        assertEquals(Code.PARAM_INVALID, ex.getCode());
        assertTrue(ex.getMessage().contains("用户ID"), "提示应含字段名，实际：" + ex.getMessage());
    }

    /** 工具类：时间格式化口径统一为 yyyy-MM-dd HH:mm:ss */
    @Test
    void dateTimeUtilsFormatsConsistently() {
        assertEquals("2026-09-18 14:46:00",
                DateTimeUtils.format(java.time.LocalDateTime.of(2026, 9, 18, 14, 46, 0)));
        assertEquals(null, DateTimeUtils.format(null), "null 应安全返回 null");
    }
}
