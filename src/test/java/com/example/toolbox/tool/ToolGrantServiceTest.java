package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具定向授权（M-17）—— 业务语义层（验收 A2 / A3 / A4 / A5 / A6 / C1 / C2 / C3 / C4 / C5 / C7）。
 *
 * <p>用户侧与工具侧共用同一 {@link ToolGrantService}，本类在真实 Spring + H2 上下文里
 * 直接调用该 Service，覆盖双向一致、全量覆盖 / 清空 / 去重、无效 ID → 1004 且不部分写入、
 * 目标不存在 → 1004、空 ID → 1003、授权给本人、彻底删除后查询不返回等核心口径。
 * 接口层（权限 403 / HTTP 语义）见 {@code ToolGrantEndpointTest}；可见范围 5 处与边界见
 * {@code ToolGrantVisibilityTest}。
 */
class ToolGrantServiceTest extends IntegrationTestBase {

    @Autowired
    private ToolGrantService toolGrantService;

    private Long deptA;
    private Long deptB;
    private Long operatorId;
    private Long targetId;
    private Long viewerId;
    private Long toolA;
    private Long toolB;

    @BeforeEach
    void setUpData() {
        deptA = insertDept("研发部");
        deptB = insertDept("市场部");
        operatorId = insertUser("admin", deptA, PermissionCode.USER_GRANT).getId();
        targetId = insertUser("grantee", deptB).getId();
        viewerId = insertUser("viewer", deptB).getId();
        toolA = insertTool("工具A", deptA);
        toolB = insertTool("工具B", deptA);
    }

    // ---------------- A2：用户侧查询 ----------------

    @Test
    void listToolGrantsEmptyWhenNone() {
        assertTrue(toolGrantService.listToolsForUser(targetId).isEmpty(), "无授权应返回空列表");
    }

    @Test
    void listToolGrantsReturnsBriefVOFieldsAndAscending() {
        toolGrantService.saveToolsForUser(targetId, List.of(String.valueOf(toolA), String.valueOf(toolB)), operatorId);

        List<ToolBriefVO> list = toolGrantService.listToolsForUser(targetId);
        assertEquals(2, list.size(), "应按 id 升序返回两个工具");
        // 升序：工具A 的 id 小于 工具B
        assertTrue(Long.parseLong(list.get(0).getId()) < Long.parseLong(list.get(1).getId()), "应按 id 升序");
        ToolBriefVO first = list.get(0);
        assertEquals("工具A", first.getName());
        assertEquals("研发部", first.getDeptName());
        assertEquals("v1.0", first.getLatestVersion());
    }

    // ---------------- A3：用户侧保存（覆盖 / 清空 / 去重） ----------------

    @Test
    void saveToolsForUserOverwritesFull() {
        toolGrantService.saveToolsForUser(targetId, List.of(String.valueOf(toolA)), operatorId);
        toolGrantService.saveToolsForUser(targetId, List.of(String.valueOf(toolB)), operatorId);

        List<ToolBriefVO> list = toolGrantService.listToolsForUser(targetId);
        assertEquals(1, list.size(), "应是全量覆盖，而非累加");
        assertEquals("工具B", list.get(0).getName());
    }

    @Test
    void saveToolsForUserEmptyClears() {
        toolGrantService.saveToolsForUser(targetId, List.of(String.valueOf(toolA), String.valueOf(toolB)), operatorId);
        toolGrantService.saveToolsForUser(targetId, List.of(), operatorId);

        assertTrue(toolGrantService.listToolsForUser(targetId).isEmpty(), "空数组应清空全部授权");
    }

    @Test
    void saveToolsForUserDedup() {
        // C2：重复 ID 不报错、不产生重复行（唯一键 + 入参去重双重保证）
        toolGrantService.saveToolsForUser(targetId, List.of(String.valueOf(toolA), String.valueOf(toolA)), operatorId);

        List<ToolBriefVO> list = toolGrantService.listToolsForUser(targetId);
        assertEquals(1, list.size(), "重复 ID 应去重为 1 行");
    }

    // ---------------- A4 / C4：无效 ID → 1004，不部分写入 ----------------

    @Test
    void saveToolsForUserInvalidToolId1004NoPartialWrite() {
        toolGrantService.saveToolsForUser(targetId, List.of(String.valueOf(toolA)), operatorId);
        // 含不存在的工具 ID：应先校验、整次不写入
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolGrantService.saveToolsForUser(targetId,
                        List.of(String.valueOf(toolA), "999999"), operatorId));
        assertEquals(Code.NOT_FOUND, ex.getCode(), "无效工具 ID 应返回 1004");

        // 已存在的授权不受影响（未发生部分写入）
        List<ToolBriefVO> list = toolGrantService.listToolsForUser(targetId);
assertEquals(1, list.size(), "校验失败不应部分写入：仅工具A 仍在");
        assertEquals("工具A", list.get(0).getName());
    }

    @Test
    void saveToolsForUserTargetUserNotExist1004() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolGrantService.saveToolsForUser(999999L,
                        List.of(String.valueOf(toolA)), operatorId));
        assertEquals(Code.NOT_FOUND, ex.getCode(), "目标用户不存在应返回 1004");
    }

    @Test
    void saveUsersForToolInvalidUserId1004() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolGrantService.saveUsersForTool(toolA,
                        List.of("999999"), operatorId));
        assertEquals(Code.NOT_FOUND, ex.getCode(), "目标工具不存在 / 用户不存在应返回 1004");
    }

    // ---------------- C3：空 ID → 1003 ----------------

    @Test
    void saveToolsForUserNullToolIds1003() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> toolGrantService.saveToolsForUser(targetId, null, operatorId));
        assertEquals(Code.PARAM_INVALID, ex.getCode(), "toolIds 缺失应返回 1003");
    }

    // ---------------- A5：工具侧查询 / 保存（与用户侧同 Service） ----------------

    @Test
    void toolSideGrantReturnsUserBriefVOFieldsAndAscending() {
        toolGrantService.saveUsersForTool(toolA,
                List.of(String.valueOf(targetId), String.valueOf(viewerId)), operatorId);

        List<UserBriefVO> list = toolGrantService.listUsersForTool(toolA);
        assertEquals(2, list.size(), "应返回两个被授权用户");
        UserBriefVO first = list.get(0);
        assertEquals("grantee", first.getUsername());
        assertEquals("测试用户", first.getRealName());
        assertEquals("市场部", first.getDeptName());
    }

    @Test
    void toolSideGrantOverwritesFull() {
        toolGrantService.saveUsersForTool(toolA, List.of(String.valueOf(targetId)), operatorId);
        toolGrantService.saveUsersForTool(toolA, List.of(String.valueOf(viewerId)), operatorId);

        List<UserBriefVO> list = toolGrantService.listUsersForTool(toolA);
        assertEquals(1, list.size(), "工具侧也应全量覆盖");
        assertEquals("viewer", list.get(0).getUsername());
    }

    // ---------------- A6：双向一致（同一张表、无缓存） ----------------

    @Test
    void bidirectionalConsistency() {
        // 用户侧保存 → 工具侧立即可见
        toolGrantService.saveToolsForUser(targetId, List.of(String.valueOf(toolA)), operatorId);
        assertTrue(toolGrantService.listUsersForTool(toolA).stream()
                .anyMatch(u -> u.getId().equals(String.valueOf(targetId))), "用户侧授权后应能在工具侧查到");

        // 工具侧保存 → 用户侧立即可见
        toolGrantService.saveUsersForTool(toolB, List.of(String.valueOf(viewerId)), operatorId);
        assertTrue(toolGrantService.listToolsForUser(viewerId).stream()
                .anyMatch(t -> t.getId().equals(String.valueOf(toolB))), "工具侧授权后应能在用户侧查到");
    }

    // ---------------- C1：授权给本人（v1.14 同部门静默丢弃） ----------------

    @Test
    void grantToSelfOk() {
        // operatorId 与 toolA 同属 deptA：本人与工具同部门属无意义配对，保存时应静默丢弃（不报错）。
        // 静默丢弃既验证「自授权不抛异常」，也验证 v1.14 收敛语义对同部门配对生效。
        toolGrantService.saveToolsForUser(operatorId, List.of(String.valueOf(toolA)), operatorId);
        assertEquals(0, toolGrantService.listToolsForUser(operatorId).size(), "本人与工具同部门：静默丢弃，不报错");
    }

    // ---------------- C5：彻底删除后查询不返回 ----------------

    @Test
    void purgedToolNotReturnedInList() {
        toolGrantService.saveToolsForUser(targetId, List.of(String.valueOf(toolA)), operatorId);
        // 模拟工具被彻底删除（工具记录消失，授权记录因级联在 purge 中清理；此处直接删主表行）
        toolMapper.deleteById(toolA);

        assertTrue(toolGrantService.listToolsForUser(targetId).isEmpty(),
                "工具主记录已不存在时，该授权不应再被返回");
    }

    // ---------------- C7：清空后立即不可见 ----------------

    @Test
    void clearThenNotVisible() {
        toolGrantService.saveToolsForUser(targetId, List.of(String.valueOf(toolA)), operatorId);
        toolGrantService.saveToolsForUser(targetId, List.of(), operatorId);
        assertTrue(toolGrantService.listToolsForUser(targetId).isEmpty(), "清空后该用户对工具立即不可见");
    }

    // ---------------- 夹具 ----------------

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

    private Long insertTool(String name, Long deptId) {
        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
        tool.setIntro("简介");
        tool.setFileExt("zip");
        tool.setScope(Scope.DEPT);
        tool.setDeptId(deptId);
        tool.setUploaderId(operatorId == null ? 1L : operatorId);
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
