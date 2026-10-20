package com.example.toolbox.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.auth.service.AuthService;
import com.example.toolbox.auth.vo.LoginVO;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.PermissionDict;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.result.PageResult;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.dto.UserStatusDTO;
import com.example.toolbox.user.dto.UserUpdateDTO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.service.UserService;
import com.example.toolbox.user.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户与权限模块（验收 A5 / A6 / C3 / C4 / C6）。
 * 覆盖：新建后可登录、编辑仅姓名与部门可改、账号重复 1005、部门不存在 1004、
 * 分页与关键词、停用自身被拒、权限全量覆盖（含清空）、非法编码 1003。
 */
class UserServiceTest extends IntegrationTestBase {

    @Autowired
    private UserService userService;
    @Autowired
    private DeptService deptService;
    @Autowired
    private AuthService authService;

    private Long deptId;
    private Long otherDeptId;

    @BeforeEach
    void setUpData() {
        // 操作人 ID 用极大值，避免与 H2 自增主键（从 1 开始）碰撞而误触"不能停用自身"守卫
        loginAs(999_999L, "admin", 1L, PermissionCode.USER_MANAGE, PermissionCode.USER_GRANT,
                PermissionCode.DEPT_MANAGE);
        DeptSaveDTO d1 = new DeptSaveDTO();
        d1.setName("研发部");
        deptId = Long.valueOf(deptService.create(d1));

        DeptSaveDTO d2 = new DeptSaveDTO();
        d2.setName("市场部");
        otherDeptId = Long.valueOf(deptService.create(d2));
    }

    private UserSaveDTO saveDto(String username, String realName, Long dept, String... perms) {
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername(username);
        dto.setRealName(realName);
        dto.setPassword("pass123456");
        dto.setDeptId(String.valueOf(dept));
        dto.setPermissions(perms.length == 0 ? null : List.of(perms));
        return dto;
    }

    /** A5：新建后可登录；密码为摘要存储 */
    @Test
    void createdUserCanLogin() {
        String id = userService.create(saveDto("zhangsan", "张三", deptId,
                PermissionCode.TOOL_DOWNLOAD));

        LoginVO login = authService.login("zhangsan", "pass123456", false);
        assertNotNull(login.getToken(), "新建账号应可登录");
        assertEquals("张三", login.getUser().getRealName());
        assertEquals(List.of(PermissionCode.TOOL_DOWNLOAD), login.getUser().getPermissions(),
                "登录信息应带出被授予的权限点");

        UserDO raw = userMapper.selectById(Long.valueOf(id));
        assertFalse(raw.getPassword().contains("pass123456"), "密码不得明文存储");
    }

    /** A5：编辑仅姓名与归属部门可改；账号不可改 */
    @Test
    void updateOnlyChangesNameAndDept() {
        String id = userService.create(saveDto("zhangsan", "张三", deptId));
        Long uid = Long.valueOf(id);

        UserUpdateDTO dto = new UserUpdateDTO();
        dto.setRealName("张三丰");
        dto.setDeptId(String.valueOf(otherDeptId));
        userService.update(uid, dto);

        UserDO after = userMapper.selectById(uid);
        assertEquals("zhangsan", after.getUsername(), "账号必须保持不变");
        assertEquals("张三丰", after.getRealName());
        assertEquals(otherDeptId, after.getDeptId(), "归属部门应已变更");

        // 结构上：UserUpdateDTO 不提供 username 字段，改账号在编译期即不可能
        assertTrue(java.util.Arrays.stream(UserUpdateDTO.class.getDeclaredFields())
                        .noneMatch(f -> f.getName().equals("username")),
                "UserUpdateDTO 不得存在 username 字段（从结构上禁止改账号）");
    }

    /** A5：账号重复 → 1005 */
    @Test
    void duplicateUsernameRejected() {
        userService.create(saveDto("zhangsan", "张三", deptId));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.create(saveDto("zhangsan", "李四", deptId)));
        assertEquals(Code.CONFLICT, ex.getCode(), "账号重复应返回 1005");
    }

    /** A5 / C4 / C6：部门不存在 → 1004，且不产生孤儿数据 */
    @Test
    void nonExistentDeptRejectedWithoutOrphanData() {
        BusinessException onNull = assertThrows(BusinessException.class,
                () -> userService.create(saveDto("zhangsan", "张三", 999999L)));
        assertEquals(Code.NOT_FOUND, onNull.getCode(), "部门不存在应返回 1004");

        assertEquals(0, userMapper.selectCount(null), "失败不得产生孤儿用户");
        assertEquals(0, userPermissionMapper.selectCount(null), "失败不得产生孤儿权限记录");

        // 编号格式合法但不存在 → 同样 1004（"0" 是合法数字，不是格式错误）
        BusinessException onZero = assertThrows(BusinessException.class,
                () -> userService.create(saveDto("lisi", "李四", 0L)));
        assertEquals(Code.NOT_FOUND, onZero.getCode(), "ID 合法但不存在应返回 1004");
        assertEquals(0, userMapper.selectCount(null), "失败不得产生孤儿用户");
    }

    /** 非法 ID **格式**（非数字）→ 1003，与"存在性"问题区分开 */
    @Test
    void malformedDeptIdReturnsParamInvalid() {
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername("zhangsan");
        dto.setRealName("张三");
        dto.setPassword("pass123456");
        dto.setDeptId("not-a-number");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.create(dto));
        assertEquals(Code.PARAM_INVALID, ex.getCode(), "非数字部门 ID 应返回 1003");
        assertEquals(0, userMapper.selectCount(null), "失败不得产生孤儿用户");
    }

    /** A5：列表支持关键词 + 部门筛选 + 分页 */
    @Test
    void pageSupportsKeywordAndDeptFilter() {
        userService.create(saveDto("zhangsan", "张三", deptId));
        userService.create(saveDto("lisi", "李四", otherDeptId));
        userService.create(saveDto("zhangfei", "张飞", deptId));

        PageResult<UserVO> byKeyword = userService.page(1, 20, "zhang", null);
        assertEquals(2, byKeyword.getTotal(), "关键词应命中账号 zhangsan / zhangfei");

        PageResult<UserVO> byName = userService.page(1, 20, "张飞", null);
        assertEquals(1, byName.getTotal(), "关键词应支持姓名匹配");

        PageResult<UserVO> byDept = userService.page(1, 20, null, String.valueOf(deptId));
        assertEquals(2, byDept.getTotal(), "按部门筛选应命中 2 人");

        PageResult<UserVO> paged = userService.page(1, 2, null, null);
        assertEquals(3, paged.getTotal(), "总数应为 3");
        assertEquals(2, paged.getList().size(), "每页 2 条");
    }

    /** A5：列表不返回权限点，详情才返回（避免列表接口拖明细） */
    @Test
    void listOmitsPermissionsDetailIncludesThem() {
        String id = userService.create(saveDto("zhangsan", "张三", deptId,
                PermissionCode.TOOL_UPLOAD));
        Long uid = Long.valueOf(id);

        UserVO listItem = userService.page(1, 20, "zhangsan", null).getList().get(0);
        assertNull(listItem.getPermissions(), "列表项不应带权限点");

        UserVO detail = userService.detail(uid);
        assertEquals(List.of(PermissionCode.TOOL_UPLOAD), detail.getPermissions(),
                "详情应带出权限点");
    }

    @Test
    void detailOfMissingUserReturnsNotFound() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.detail(999999L));
        assertEquals(Code.NOT_FOUND, ex.getCode());
    }

    /** A6：权限全量覆盖保存，含"传空集合 = 清空" */
    @Test
    void savePermissionsOverwritesInFull() {
        String id = userService.create(saveDto("zhangsan", "张三", deptId));
        Long uid = Long.valueOf(id);

        userService.savePermissions(uid, List.of(PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD));
        assertEquals(2, userService.getPermissions(uid).size(), "应写入 2 个权限点");

        // 全量覆盖：只留一个
        userService.savePermissions(uid, List.of(PermissionCode.TAG_MANAGE));
        assertEquals(List.of(PermissionCode.TAG_MANAGE), userService.getPermissions(uid),
                "应全量覆盖，旧权限点被移除");

        // 空集合 = 清空
        userService.savePermissions(uid, List.of());
        assertTrue(userService.getPermissions(uid).isEmpty(), "传空集合应清空权限");

        // null 亦按清空处理（防御式口径）
        userService.savePermissions(uid, null);
        assertTrue(userService.getPermissions(uid).isEmpty(), "传 null 应清空权限");
    }

    /** A6 / C3：编码非法 → 1003，且说明是哪个编码 */
    @Test
    void invalidPermissionCodeRejectedWithCodeInMessage() {
        String id = userService.create(saveDto("zhangsan", "张三", deptId));
        Long uid = Long.valueOf(id);
        userService.savePermissions(uid, List.of(PermissionCode.TOOL_UPLOAD));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.savePermissions(uid, List.of("tool:not-exist")));
        assertEquals(Code.PARAM_INVALID, ex.getCode(), "非法编码应返回 1003");
        assertTrue(ex.getMessage().contains("tool:not-exist"),
                "提示应指明非法编码，实际：" + ex.getMessage());

        // 事务回滚：原权限点未被清掉
        assertEquals(List.of(PermissionCode.TOOL_UPLOAD), userService.getPermissions(uid),
                "非法编码导致失败时，原有权限点应保持不变");
    }

    /** 权限点字典：9 个（v1.23 / M-25 新增 tool:assign）、分组、编码唯一 */
    @Test
    void permissionDictHasEightUniqueCodesInGroups() {
        List<String> codes = PermissionDict.allCodes();
        assertEquals(9, codes.size(), "权限点应为 9 个");
        assertEquals(9, codes.stream().distinct().count(), "权限点编码不得重复");
        assertEquals(5, PermissionDict.GROUPS.size(), "分组应为 5 组");
        assertTrue(PermissionDict.isValidCode(PermissionCode.SYS_SETTING));
        assertFalse(PermissionDict.isValidCode("nope"));
    }

    /** 停用自身被拒（防止管理员把自己锁出系统）；停用他人成功且登录立即失效 */
    @Test
    void cannotDisableSelfButCanDisableOthers() {
        String id = userService.create(saveDto("zhangsan", "张三", deptId));
        Long uid = Long.valueOf(id);

        // 视图：当前登录者即 zhang san
        loginAs(uid, "zhangsan", deptId, PermissionCode.USER_MANAGE);
        UserStatusDTO disable = new UserStatusDTO();
        disable.setStatus(0);

        BusinessException self = assertThrows(BusinessException.class,
                () -> userService.updateStatus(uid, disable));
        assertEquals(Code.CONFLICT, self.getCode(), "停用自身应返回 1005");

        // 停用他人
        String otherId = userService.create(saveDto("lisi", "李四", deptId));
        Long other = Long.valueOf(otherId);
        userService.updateStatus(other, disable);

        BusinessException login = assertThrows(BusinessException.class,
                () -> authService.login("lisi", "pass123456", false));
        assertEquals(Code.BAD_CREDENTIALS, login.getCode(),
                "停用后应无法登录（统一 1006，不暴露停用原因）");
    }

    /** 停用幂等 + 可重新启用 */
    @Test
    void statusChangeIsIdempotentAndReversible() {
        String id = userService.create(saveDto("zhangsan", "张三", deptId));
        Long uid = Long.valueOf(id);

        UserStatusDTO disable = new UserStatusDTO();
        disable.setStatus(0);
        userService.updateStatus(uid, disable);
        userService.updateStatus(uid, disable); // 幂等
        assertEquals(0, userMapper.selectById(uid).getStatus(), "应已停用");

        UserStatusDTO enable = new UserStatusDTO();
        enable.setStatus(1);
        userService.updateStatus(uid, enable);
        assertEquals(1, userMapper.selectById(uid).getStatus(), "应已启用");
        assertNotNull(authService.login("zhangsan", "pass123456", false).getToken(), "启用后应可登录");
    }

    @Test
    void invalidStatusValueRejected() {
        String id = userService.create(saveDto("zhangsan", "张三", deptId));
        Long uid = Long.valueOf(id);
        UserStatusDTO bad = new UserStatusDTO();
        bad.setStatus(7);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateStatus(uid, bad));
        assertEquals(Code.PARAM_INVALID, ex.getCode(), "非法状态应返回 1003");
    }

    /** 权限改动立即生效：登录上下文实时从库读取（不依赖旧令牌） */
    @Test
    void permissionChangeTakesEffectImmediately() {
        String id = userService.create(saveDto("zhangsan", "张三", deptId));
        Long uid = Long.valueOf(id);
        // v1.14：create 默认 tool:download；本用例显式清空以保留「初始无权限」基线
        // （默认权限行为由 UserDefaultPermissionTest A6/A7/A8 专门覆盖）
        userService.savePermissions(uid, List.of());

        assertEquals(0, authService.login("zhangsan", "pass123456", false)
                .getUser().getPermissions().size(), "初始无权限");

        userService.savePermissions(uid, List.of(PermissionCode.TOOL_UPLOAD));
        // 同一账号已在上一步建立会话：重新登录须显式 force（M-18 契约行为）
        assertEquals(List.of(PermissionCode.TOOL_UPLOAD),
                authService.login("zhangsan", "pass123456", true).getUser().getPermissions(),
                "重新登录应带出新权限");
    }

    /** 数据隔离基线：用户表不会出现任何未归部门的记录 */
    @Test
    void everyUserHasDept() {
        userService.create(saveDto("zhangsan", "张三", deptId));
        userService.create(saveDto("lisi", "李四", otherDeptId));

        long nullDept = userMapper.selectCount(
                Wrappers.<UserDO>lambdaQuery().isNull(UserDO::getDeptId));
        assertEquals(0, nullDept, "不得存在无归属部门的用户（一人一部门为硬约束）");
    }
}
