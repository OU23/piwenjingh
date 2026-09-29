package com.example.toolbox.ws;

import com.example.toolbox.auth.service.AuthService;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.security.SessionRegistry;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 实时通道端到端（M-18 / 契约 §3.3）。
 *
 * <p>真实容器（{@code RANDOM_PORT}）+ 真实 WebSocket 客户端，覆盖验收
 * <b>A7（握手鉴权）/ A8（被踢推送 + 关闭码 4002）/ C9（未登录拒绝）</b>。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebSocketChannelTest {

    private static final String USERNAME = "ws-user";
    private static final String PASSWORD = "pass123456";

    @LocalServerPort
    private int port;

    @Autowired
    private AuthService authService;
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

    @BeforeEach
    void setUpData() {
        DeptDO d = new DeptDO();
        d.setName("实时通道部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);

        UserDO u = new UserDO();
        u.setUsername(USERNAME);
        u.setPassword(passwordEncoder.encode(PASSWORD));
        u.setRealName("实时用户");
        u.setDeptId(d.getId());
        u.setStatus(StatusValue.ENABLED);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);

        UserPermissionDO up = new UserPermissionDO();
        up.setUserId(u.getId());
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

    private CompletableFuture<WebSocketSession> connect(Capture handler, String token) {
        return client.execute(handler, "ws://localhost:" + port + "/ws?token=" + token);
    }

    /** A7：有效令牌可建立连接 */
    @Test
    void validTokenConnects() throws Exception {
        String token = authService.login(USERNAME, PASSWORD, false).getToken();

        WebSocketSession session = connect(new Capture(), token).get(5, TimeUnit.SECONDS);
        assertTrue(session.isOpen(), "有效令牌应可建立连接");
        session.close();
    }

    /** A7 / C9：无效令牌 / 缺少令牌 → 拒绝连接（不得匿名接入） */
    @Test
    void invalidOrMissingTokenRejected() {
        assertThrows(Exception.class, () -> connect(new Capture(), "not-a-jwt").get(5, TimeUnit.SECONDS));
        assertThrows(Exception.class, () -> connect(new Capture(), "").get(5, TimeUnit.SECONDS));
    }

    /** A8：旧会话被新登录替代 → 收到 FORCE_LOGOUT 推送并以 4002 关闭 */
    @Test
    void replacedSessionReceivesForceLogoutAnd4002() throws Exception {
        String firstToken = authService.login(USERNAME, PASSWORD, false).getToken();
        Capture cap = new Capture();
        WebSocketSession session = connect(cap, firstToken).get(5, TimeUnit.SECONDS);

        // 第二处强制登录 → 旧连接应收到推送并被关闭
        authService.login(USERNAME, PASSWORD, true);

        CloseStatus status = cap.closed.get(5, TimeUnit.SECONDS);
        assertEquals(4002, status.getCode(), "被踢连接应以 4002 关闭");
        assertTrue(cap.messages.stream().anyMatch(m -> m.contains("FORCE_LOGOUT")),
                "应先收到 FORCE_LOGOUT 推送，实际：" + cap.messages);
        assertFalse(session.isOpen(), "被踢后连接应已关闭");
    }
}
