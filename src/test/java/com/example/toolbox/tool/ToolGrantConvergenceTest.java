package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolGrantDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.mapper.ToolGrantMapper;
import com.example.toolbox.tool.service.ToolGrantService;
import com.example.toolbox.tool.service.ToolService;
import com.example.toolbox.tool.vo.ToolBriefVO;
import com.example.toolbox.tool.vo.UserBriefVO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定向授权"无意义配对"收敛（v1.14 / 计划 P11 T2 / T3 / T5）—— 业务语义层。
 *
 * <p>覆盖：工具侧保存静默丢弃同部门人员（A2 / A3）、用户侧保存静默丢弃 PUBLIC / 同部门工具（A4）、
 * 缺 / 删 ID 仍整体 {@code 1004} 不部分成功（A5 回归）、"已授权"返回过滤无意义配对（A9 / A10 / A11）。
 *
 * <p>与 {@code ToolGrantServiceTest}（M-17 基础语义）互补：本类只验 v1.14 增量，不动既有断言。
 * "静默丢弃 = 不写入"通过<b>直接查授权表</b>证明（而非仅靠返回过滤，否则无法区分"藏起来"与"没写"）。
 */
class ToolGrantConvergenceTest extends IntegrationTestBase {

    @Autowired
    private ToolGrantService toolGrantService;
    @Autowired
    private ToolGrantMapper grantMapper;

    private Long deptA;
    private Long deptB;
    private Long deptC;
    private Long operatorId;
    private Long sameDeptUser;   // 与工具同部门（deptA）
    private Long crossDeptUser;   // 跨部门（deptB）
    private Long targetUser;      // 用户侧授权目标（deptB）
    private Long toolDeptA;       // DEPT + deptA（工具侧测试主体）
    private Long toolPublic;      // PUBLIC + deptA
    private Long toolSameDept;    // DEPT + deptB（与目标同部门）
    private Long toolCrossA;      // DEPT + deptA（与目标跨部门）
    private Long toolCrossC;      // DEPT + deptC（与目标跨部门）

    @BeforeEach
    void setUpData() {
        deptA = insertDept("研发部");
        deptB = insertDept("市场部");
        deptC = insertDept("财务部");
        operatorId = insertUser("admin", deptA, PermissionCode.USER_GRANT).getId();
        sameDeptUser = insertUser("same_dept", deptA).getId();
        crossDeptUser = insertUser("cross_dept", deptB).getId();
        targetUser = insertUser("grantee", deptB).getId();
        toolDeptA = insertTool("工具A", Scope.DEPT, deptA);
        toolPublic = insertTool("工具公共", Scope.PUBLIC, deptA);
        toolSameDept = insertTool("工具同部", Scope.DEPT, deptB);
        toolCrossA = insertTool("工具跨A", Scope.DEPT, deptA);
        toolCrossC = insertTool("工具跨C", Scope.DEPT, deptC);
    }

    // ---------------- A2 / A3：工具侧保存静默丢弃同部门人员 ----------------

    @Test
    void toolSideSilentlyDropsSameDeptUserButKeepsCrossDept() {
        toolGrantService.saveUsersForTool(toolDeptA,
                List.of(String.valueOf(sameDeptUser), String.valueOf(crossDeptUser)), operatorId);

        // 静默丢弃 = 不写入（直接查表证明，而非仅靠返回过滤）
        assertEquals(0, grantRowCount(toolDeptA, sameDeptUser), "同部门人员应被静默丢弃，不写入授权表");
        assertEquals(1, grantRowCount(toolDeptA, crossDeptUser), "跨部门人员应正常写入");

        List<UserBriefVO> list = toolGrantService.listUsersForTool(toolDeptA);
        assertTrue(list.stream().anyMatch(u -> u.getId().equals(String.valueOf(crossDeptUser))),
                "跨部门用户应在已授权列表");
        assertFalse(list.stream().anyMatch(u -> u.getId().equals(String.valueOf(sameDeptUser))),
                "同部门用户不应出现在已授权列表");
    }

    @Test
    void toolSideKeepsOnlyCrossDeptUser() {
        toolGrantService.saveUsersForTool(toolDeptA, List.of(String.valueOf(crossDeptUser)), operatorId);
        assertEquals(1, toolGrantService.listUsersForTool(toolDeptA).size(), "仅跨部门用户应被授权");
    }

    // ---------------- A4：用户侧保存静默丢弃 PUBLIC / 同部门工具 ----------------

    @Test
    void userSideSilentlyDropsPublicAndSameDeptTools() {
        toolGrantService.saveToolsForUser(targetUser,
                List.of(String.valueOf(toolPublic), String.valueOf(toolSameDept),
                        String.valueOf(toolCrossA), String.valueOf(toolCrossC)), operatorId);

        assertEquals(0, grantRowCount(toolPublic, targetUser), "PUBLIC 工具应被静默丢弃，不写入");
        assertEquals(0, grantRowCount(toolSameDept, targetUser), "与用户同部门工具应被静默丢弃，不写入");
        assertEquals(1, grantRowCount(toolCrossA, targetUser), "跨部门工具 A 应正常写入");
        assertEquals(1, grantRowCount(toolCrossC, targetUser), "跨部门工具 C 应正常写入");

        List<ToolBriefVO> list = toolGrantService.listToolsForUser(targetUser);
        assertTrue(list.stream().anyMatch(t -> t.getId().equals(String.valueOf(toolCrossA))),
                "跨部门工具 A 应在已授权列表");
        assertTrue(list.stream().anyMatch(t -> t.getId().equals(String.valueOf(toolCrossC))),
                "跨部门工具 C 应在已授权列表");
        assertFalse(list.stream().anyMatch(t -> t.getId().equals(String.valueOf(toolPublic))),
                "PUBLIC 工具不应出现在已授权列表");
        assertFalse(list.stream().anyMatch(t -> t.getId().equals(String.valueOf(toolSameDept))),
                "同部门工具不应出现在已授权列表");
    }

    // ---------------- A5：缺 / 删 ID 仍整体 1004，不部分成功（回归） ----------------

    @Test
    void userSideInvalidToolIdStill1004NoPartialWrite() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolGrantService.saveToolsForUser(targetUser,
                        List.of(String.valueOf(toolCrossA), "999999"), operatorId));
        assertEquals(Code.NOT_FOUND, ex.getCode(), "无效工具 ID 仍应返回 1004");
        assertEquals(0, grantRowCount(toolCrossA, targetUser), "校验失败不应部分写入");
    }

    @Test
    void toolSideInvalidUserIdStill1004() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolGrantService.saveUsersForTool(toolDeptA,
                        List.of("999999"), operatorId));
        assertEquals(Code.NOT_FOUND, ex.getCode(), "无效用户 ID 仍应返回 1004");
    }

    // ---------------- A9 / A11：用户侧"已授权"返回过滤（含历史脏数据） ----------------

    @Test
    void userSideReturnExcludesPublicTool() {
        // 直接插入历史 PUBLIC 授权记录（绕过保存静默丢弃），验证返回过滤
        insertGrant(toolPublic, targetUser);
        insertGrant(toolCrossA, targetUser);

        List<ToolBriefVO> list = toolGrantService.listToolsForUser(targetUser);
        assertTrue(list.stream().anyMatch(t -> t.getId().equals(String.valueOf(toolCrossA))),
                "跨部门工具应返回");
        assertFalse(list.stream().anyMatch(t -> t.getId().equals(String.valueOf(toolPublic))),
                "PUBLIC 工具即便历史存在也不应返回");
    }

    @Test
    void userSideReturnExcludesSameDeptTool() {
        insertGrant(toolSameDept, targetUser);
        insertGrant(toolCrossA, targetUser);

        List<ToolBriefVO> list = toolGrantService.listToolsForUser(targetUser);
        assertTrue(list.stream().anyMatch(t -> t.getId().equals(String.valueOf(toolCrossA))),
                "跨部门工具应返回");
        assertFalse(list.stream().anyMatch(t -> t.getId().equals(String.valueOf(toolSameDept))),
                "与用户同部门工具即便历史存在也不应返回");
    }

    // ---------------- A10 / A11：工具侧"已授权"返回过滤（含历史脏数据） ----------------

    @Test
    void toolSideReturnExcludesSameDeptUser() {
        insertGrant(toolDeptA, sameDeptUser);
        insertGrant(toolDeptA, crossDeptUser);

        List<UserBriefVO> list = toolGrantService.listUsersForTool(toolDeptA);
        assertTrue(list.stream().anyMatch(u -> u.getId().equals(String.valueOf(crossDeptUser))),
                "跨部门用户应返回");
        assertFalse(list.stream().anyMatch(u -> u.getId().equals(String.valueOf(sameDeptUser))),
                "工具所属部门人员即便历史存在也不应返回");
    }

    // ---------------- 夹具 ----------------

    private long grantRowCount(Long toolId, Long userId) {
        // 注意：MyBatis-Plus 的 selectList(lambdaQuery 双 eq) 在本 H2 测试库下会出现假阴性（返回 0），
        // 与早前 selectCount 双 eq 不可靠同源。改用专用 @Select 计数（已验证可靠）。
        return grantMapper.countByToolAndUser(toolId, userId);
    }

    private void insertGrant(Long toolId, Long userId) {
        ToolGrantDO g = new ToolGrantDO();
        g.setToolId(toolId);
        g.setUserId(userId);
        g.setGrantBy(operatorId);
        g.setCreateTime(LocalDateTime.now());
        grantMapper.insert(g);
    }

    private Long insertDept(String name) {
        DeptDO d = new DeptDO();
        d.setName(name);
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        return d.getId();
    }

    private UserDO insertUser(String username, Long deptId, String... perms) {
        UserDO u = new UserDO();
        u.setUsername(username);
        u.setPassword("$2a$10$placeholder-shape-valid");
        u.setRealName("测试用户");
        u.setDeptId(deptId);
        u.setStatus(StatusValue.ENABLED);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);
        for (String p : perms) {
            UserPermissionDO up = new UserPermissionDO();
            up.setUserId(u.getId());
            up.setPermissionCode(p);
            up.setCreateTime(LocalDateTime.now());
            userPermissionMapper.insert(up);
        }
        return u;
    }

    private Long insertTool(String name, String scope, Long deptId) {
        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
        tool.setIntro("简介");
        tool.setFileExt("zip");
        tool.setScope(scope);
        tool.setDeptId(deptId);
        tool.setUploaderId(operatorId);
        tool.setLatestVersion("v1.0");
        tool.setFileSize(8L);
        tool.setDownloadCount(0);
        tool.setDeleted(ToolService.NOT_DELETED);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        tool.setLatestVersionId(0L);
        toolMapper.insert(tool);

        ToolVersionDO v = new ToolVersionDO();
        v.setToolId(tool.getId());
        v.setVersion("v1.0");
        v.setFileKey("fake-key-" + name);
        v.setFileName(name + ".zip");
        v.setFileExt("zip");
        v.setFileSize(8L);
        v.setUploaderId(tool.getUploaderId());
        v.setDownloadCount(0);
        v.setCreateTime(now);
        toolVersionMapper.insert(v);

        tool.setLatestVersionId(v.getId());
        toolMapper.updateById(tool);
        return tool.getId();
    }
}
