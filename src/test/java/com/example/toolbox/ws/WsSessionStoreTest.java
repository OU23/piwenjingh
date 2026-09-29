package com.example.toolbox.ws;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket 连接注册表（M-18）：推送、关闭码、重复连接、异常吞掉。
 * 覆盖验收 <b>A8（被踢推送 + 4002）/ C6（重复连接）</b>。
 */
class WsSessionStoreTest {

    private final WsSessionStore store = new WsSessionStore();

    private WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void registerThenSend() throws Exception {
        WebSocketSession s = session("s1");
        store.register(7L, s);
        assertTrue(store.isConnected(7L));

        store.send(7L, "{\"type\":\"FORCE_LOGOUT\"}");

        verify(s).sendMessage(any(TextMessage.class));
    }

    /** A8：被踢时"先消息、后关闭（4002）"（双保险的顺序） */
    @Test
    void sendAndClosePushesThenClosesWith4002() throws Exception {
        WebSocketSession s = session("s1");
        store.register(7L, s);

        store.sendAndClose(7L, "bye", WsCloseCode.SESSION_REPLACED, "session replaced");

        InOrder inOrder = inOrder(s);
        inOrder.verify(s).sendMessage(any(TextMessage.class));
        inOrder.verify(s).close(new CloseStatus(WsCloseCode.SESSION_REPLACED, "session replaced"));
        assertFalse(store.isConnected(7L), "被踢后连接应注销");
    }

    /** C6：同一账号第二条连接建立时，旧连接以 4003 关闭，不得两条并存 */
    @Test
    void duplicateConnectionClosesOldWith4003() throws Exception {
        WebSocketSession first = session("s1");
        WebSocketSession second = session("s2");

        store.register(7L, first);
        store.register(7L, second);

        verify(first).close(new CloseStatus(WsCloseCode.DUPLICATE_CONNECTION, "another connection established"));
        assertTrue(store.isConnected(7L), "应保留较新的连接");
    }

    /** 推送失败必须被吞掉（不得影响登录 / 登出 / 停用主流程） */
    @Test
    void sendFailureIsSwallowed() throws Exception {
        WebSocketSession s = session("s1");
        doThrow(new IOException("boom")).when(s).sendMessage(any(TextMessage.class));
        store.register(7L, s);

        assertDoesNotThrow(() -> store.send(7L, "x"));
        assertDoesNotThrow(() -> store.close(7L, WsCloseCode.TOKEN_INVALID, "logout"));
    }

    @Test
    void unknownOrNullUserIsSafe() {
        assertDoesNotThrow(() -> store.send(null, "x"));
        assertDoesNotThrow(() -> store.send(99L, "x"));
        assertDoesNotThrow(() -> store.close(99L, WsCloseCode.TOKEN_INVALID, "x"));
        assertFalse(store.isConnected(99L));
    }
}
