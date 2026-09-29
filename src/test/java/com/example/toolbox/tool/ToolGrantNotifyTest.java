package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.service.ToolService;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.ws.WsSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B11 / 契约 v1.17：**定向授权变更即时通知**（`GRANT_CHANGED`）。
 *
 * <p>覆盖验收 A1–A7：
 * <ul>
 *   <li>用户侧 / 工具侧保存后，**被影响用户**收到 1 条 {@code GRANT_CHANGED}（工具侧 = **新增 ∪ 被移除**）；</li>
 *   <li>**无实际变化**（重复保存同一集合 / 全为被静默丢弃的无意义配对）→ **不推送**；</li>
 *   <li>**豁免操作人**；</li>
 *   <li>⚠️ **只推不关**：连接**不被关闭**（与 {@code PERMISSION_CHANGED} 的 4004 形成对照）；</li>
 *   <li>推送失败**不影响保存结果**。</li>
 * </ul>
 *
 * <p>推送发生在 Controller（事务提交后），故本类走接口层（MockMvc）而非直接调 service。
 */
@AutoConfigureMockMvc
class ToolGrantNotifyTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WsSessionStore wsSessionStore;

    /** 操作人所在部门（也是被授权用户所在部门） */
    private Long deptA;
    /** 工具所属部门（与 deptA 不同 → 跨部门"有意义配对"） */
    private Long deptB;

    private Long operatorId;
    private Long targetId;
    private Long otherId;
    /** deptB 的 DEPT 工具（跨部门授权才有意义） */
    private Long toolB;
    /** deptA 的 DEPT 工具（同部门 → 无意义配对） */
    private Long toolA;

    @BeforeEach
    void setUpData() {
        deptA = insertDept("研发部");
        deptB = insertDept("市场部");
        operatorId = insertUser("op", deptA);
        targetId = insertUser("target", deptA);
        otherId = insertUser("other", deptA);
        toolB = insertTool("他部门工具", deptB, Scope.DEPT);
        toolA = insertTool("本部门工具", deptA, Scope.DEPT);
    }

    // ---------------- A1：用户侧推送 ----------------

    @Test
    void userSideSaveNotifiesTargetUser() throws Exception {
        WebSocketSession targetSession = registerSession(targetId);

        saveUserGrants(targetId, toolB).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(targetSession).sendMessage(captor.capture());
        // A6：消息体
        assertThat(captor.getValue().getPayload()).contains("\"type\":\"GRANT_CHANGED\"");
        assertThat(captor.getValue().getPayload()).contains("你的可下载工具已更新");
    }

    /** A5：**只推不关** —— 连接保持（未调用任何 close） */
    @Test
    void notificationKeepsConnectionOpen() throws Exception {
        WebSocketSession targetSession = registerSession(targetId);

        saveUserGrants(targetId, toolB).andExpect(jsonPath("$.code").value(0));

        verify(targetSession).sendMessage(any(TextMessage.class));
        verify(targetSession, never()).close(any(CloseStatus.class));
    }

    // ---------------- A2：工具侧 新增 ∪ 被移除 ----------------

    @Test
    void toolSideSaveNotifiesAddedAndRemovedUsers() throws Exception {
        // 预置：该工具已授权给 target
        saveUserGrants(targetId, toolB).andExpect(jsonPath("$.code").value(0));

        WebSocketSession targetSession = registerSession(targetId);
        WebSocketSession otherSession = registerSession(otherId);

        // 改为只授权 other → target 被移除（受影响）、other 新增（受影响）
        saveToolGrants(toolB, otherId).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        verify(targetSession).sendMessage(any(TextMessage.class));
        verify(otherSession).sendMessage(any(TextMessage.class));
    }

    /** 未受影响者（本就在且仍在）不收到 */
    @Test
    void unchangedUserIsNotNotified() throws Exception {
        saveUserGrants(targetId, toolB).andExpect(jsonPath("$.code").value(0));
        saveUserGrants(otherId, toolB).andExpect(jsonPath("$.code").value(0));

        WebSocketSession otherSession = registerSession(otherId);

        // 保持 target + other 不变（顺序无关）
        saveToolGrants(toolB, targetId, otherId).andExpect(jsonPath("$.code").value(0));

        verify(otherSession, never()).sendMessage(any(TextMessage.class));
    }

    // ---------------- A3：无变化 / 无意义配对 → 不推送 ----------------

    @Test
    void identicalSaveDoesNotNotify() throws Exception {
        saveUserGrants(targetId, toolB).andExpect(jsonPath("$.code").value(0));

        WebSocketSession targetSession = registerSession(targetId);
        // 幂等：再保存完全相同的集合
        saveUserGrants(targetId, toolB).andExpect(jsonPath("$.code").value(0));

        verify(targetSession, never()).sendMessage(any(TextMessage.class));
    }

    /** ★ 铁证：diff 基于**落库结果**而非入参 —— 同部门配对会被静默丢弃，不应产生通知 */
    @Test
    void meaninglessPairDroppedByV114DoesNotNotify() throws Exception {
        WebSocketSession targetSession = registerSession(targetId);

        // toolA 属 deptA，target 也在 deptA → v1.14 静默丢弃，落库前后都是空集 → 无变化
        saveUserGrants(targetId, toolA).andExpect(jsonPath("$.code").value(0));

        verify(targetSession, never()).sendMessage(any(TextMessage.class));
    }

    // ---------------- A4：豁免操作人 ----------------

    @Test
    void operatorIsExempted() throws Exception {
        WebSocketSession operatorSession = registerSession(operatorId);

        // 操作人给自己授权（deptA 用户在 deptB 工具上）→ 受影响集合含操作人，但应被豁免
        saveUserGrants(operatorId, toolB).andExpect(jsonPath("$.code").value(0));

        verify(operatorSession, never()).sendMessage(any(TextMessage.class));
    }

    // ---------------- A7：推送失败不影响保存 ----------------

    @Test
    void pushFailureDoesNotAffectSave() throws Exception {
        WebSocketSession broken = registerSession(targetId);
        doThrow(new IOException("boom")).when(broken).sendMessage(any(TextMessage.class));

        saveUserGrants(targetId, toolB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 授权已落库（保存不受推送失败影响）
        assertThat(toolGrantMapper.selectToolIdsByUser(targetId)).containsExactly(toolB);
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

    private Long insertUser(String username, Long deptId) {
        UserDO u = new UserDO();
        u.setUsername(username);
        u.setPassword("$2a$10$not-a-real-hash-but-shape-valid-placeholder");
        u.setRealName(username);
        u.setDeptId(deptId);
        u.setStatus(StatusValue.ENABLED);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);
        return u.getId();
    }

    private Long insertTool(String name, Long deptId, String scope) {
        LocalDateTime now = LocalDateTime.now();
        ToolDO tool = new ToolDO();
        tool.setName(name);
        tool.setIntro("简介");
        tool.setFileExt("zip");
        tool.setScope(scope);
        tool.setDeptId(deptId);
        tool.setUploaderId(9_999_999L);
        tool.setLatestVersion("v1.0");
        tool.setFileSize(4L);
        tool.setDownloadCount(0);
        tool.setDeleted(ToolService.NOT_DELETED);
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        tool.setLatestVersionId(0L);
        toolMapper.insert(tool);
        return tool.getId();
    }

    /** 注册一个"在线"的假连接（Mockito mock），返回它供断言 */
    private WebSocketSession registerSession(Long userId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s-" + userId);
        when(session.isOpen()).thenReturn(true);
        wsSessionStore.register(userId, session);
        return session;
    }

    private String operatorToken(String... perms) {
        String[] codes = perms.length == 0 ? new String[] {PermissionCode.USER_GRANT} : perms;
        return signToken(LoginUser.builder()
                .userId(operatorId)
                .username("op")
                .realName("操作人")
                .deptId(deptA)
                .permissions(Set.of(codes))
                .build());
    }

    /** 用户侧：给某用户设置工具授权（覆盖式） */
    private org.springframework.test.web.servlet.ResultActions saveUserGrants(Long userId, Long... toolIds)
            throws Exception {
        return mockMvc.perform(put("/api/users/" + userId + "/tool-grants")
                .header("Authorization", "Bearer " + operatorToken())
                .contentType("application/json")
                .content("{\"toolIds\":" + jsonIds(toolIds) + "}"));
    }

    /** 工具侧：设置某工具的授权用户（覆盖式） */
    private org.springframework.test.web.servlet.ResultActions saveToolGrants(Long toolId, Long... userIds)
            throws Exception {
        return mockMvc.perform(put("/api/tools/" + toolId + "/grants")
                .header("Authorization", "Bearer " + operatorToken())
                .contentType("application/json")
                .content("{\"userIds\":" + jsonIds(userIds) + "}"));
    }

    private String jsonIds(Long... values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values[i]).append('"');
        }
        return sb.append(']').toString();
    }
}
