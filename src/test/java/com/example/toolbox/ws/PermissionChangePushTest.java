package com.example.toolbox.ws;

import com.example.toolbox.auth.service.AuthService;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.security.SessionRegistry;
import com.example.toolbox.security.SessionService;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import com.example.toolbox.user.mapper.UserMapper;
import com.example.toolbox.user.mapper.UserPermissionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限变更的 WebSocket 通知（M-21 / 契约 §3.3 v1.12）。
 *
 * <p>真实容器（{@code RANDOM_PORT}）+ 真实 WebSocket 客户端，覆盖验收 <b>A2</b> 与 <b>A8 的"解耦"面</b>：
 * <ul>
 *   <li>收到 {@code PERMISSION_CHANGED} 推送，并以关闭码 <b>4004</b> 关闭连接；</li>
 *   <li>**无 WS 连接时仍移除会话**（推送只负责告知，会话移除才是权限生效的边界）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PermissionChangePushTest {

    private static final String USERNAME = "pc-user";
    private static final String PASSWORD = "pass123456";

    @LocalServerPort
    private int port;

    @Autowired
    private AuthService authService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private SessionRegistry sessionRegistry;
    @Autowired
    private WsSessionStore wsSessionStore;
    @Autowired
    private DeptMapper deptMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private UserPermissionMapper userPermissionMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final StandardWebSocketClient client = new StandardWebSocketClient();

    private Long userId;

    @BeforeEach
    void setUpData() {
        DeptDO d = new DeptDO();
        d.setName("权限推送部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);

        UserDO u = new UserDO();
        u.setUsername(USERNAME);
        u.setPassword(passwordEncoder.encode(PASSWORD));
        u.setRealName("推送用户");
        u.setDeptId(d.getId());
        u.setStatus(StatusValue.ENABLED);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);
        userId = u.getId();

        UserPermissionDO up = new UserPermissionDO();
        up.setUserId(userId);
        up.setPermissionCode(PermissionCode.TOOL_DOWNLOAD);
        up.setCreateTime(LocalDateTime.now());
        userPermissionMapper.insert(up);
    }

    @AfterEach
    void cleanUp() {
        userPermissionMapper.delete(null);
        userMapper.delete(null);
        deptMapper.delete(null);
        sessionRegistry.clear();
        wsSessionStore.clear();
    }

    /** 收集服务端消息与关闭状态的测试用连接处理器 */
    static class Capture extends TextWebSocketHandler {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }
    }

    /** A2：权限变更 → 收到 PERMISSION_CHANGED 推送并以 4004 关闭 */
    @Test
    void permissionChangePushesMessageAndClosesWith4004() throws Exception {
        String token = authService.login(USERNAME, PASSWORD, false).getToken();
        Capture cap = new Capture();
        WebSocketSession session = client
                .execute(cap, "ws://localhost:" + port + "/ws?token=" + token)
                .get(5, TimeUnit.SECONDS);
        assertTrue(session.isOpen(), "有效令牌应可建立连接");

        sessionService.forceLogoutByPermissionChange(userId);

        CloseStatus status = cap.closed.get(5, TimeUnit.SECONDS);
        assertEquals(4004, status.getCode(), "权限变更应以 4004 关闭，实际：" + cap.messages);
        assertTrue(cap.messages.stream().anyMatch(m -> m.contains("PERMISSION_CHANGED")),
                "应先收到 PERMISSION_CHANGED 推送，实际：" + cap.messages);
        assertTrue(cap.messages.stream().anyMatch(m -> m.contains("账号发生异常，请重新登录")),
                "文案应为「账号发生异常，请重新登录」，实际：" + cap.messages);
        assertFalse(session.isOpen(), "被终结后连接应已关闭");
    }

    /** A8 的"解耦"面：**无 WS 连接**时仍移除会话（推送失败不得影响权限生效） */
    @Test
    void sessionRemovedEvenWithoutWsConnection() {
        String sid = "probe-sid-" + userId;
        sessionRegistry.establish(userId, sid);
        assertTrue(sessionRegistry.isCurrent(userId, sid), "前置：会话应为当前会话");

        assertDoesNotThrow(() -> sessionService.forceLogoutByPermissionChange(userId),
                "无 WS 连接不应抛异常");

        assertFalse(sessionRegistry.isCurrent(userId, sid),
                "★ 会话必须被移除 —— 这才是权限真正生效的边界");
    }

    /** 幂等：对无会话账号重复调用不报错 */
    @Test
    void repeatedTerminationIsIdempotent() {
        assertDoesNotThrow(() -> sessionService.forceLogoutByPermissionChange(999_999_999L));
        assertDoesNotThrow(() -> sessionService.forceLogoutByPermissionChange(null));
    }
}
