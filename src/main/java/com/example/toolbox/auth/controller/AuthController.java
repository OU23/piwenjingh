package com.example.toolbox.auth.controller;

import com.example.toolbox.auth.dto.LoginDTO;
import com.example.toolbox.auth.service.AuthService;
import com.example.toolbox.auth.vo.CurrentUserVO;
import com.example.toolbox.auth.vo.LoginVO;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.R;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.SessionService;
import com.example.toolbox.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证（契约 §三 /api/auth）。
 * login 为公开接口（见 PublicPathProperties 白名单）；logout / me 需登录。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditLogger auditLogger;
    private final SessionService sessionService;

    @PostMapping("/login")
    public R<LoginVO> login(@RequestBody @Valid LoginDTO dto) {
        return R.ok(authService.login(dto.getUsername(), dto.getPassword(), dto.getForce()));
    }

    /**
     * 登出（M-18 / 契约 §3.2）：移除该账号的会话记录并关闭其 WebSocket 连接，
     * 此后原令牌 → 401。幂等：会话不存在也返回成功（不报 500）。
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        LoginUser user = UserContext.get();
        if (user != null) {
            sessionService.logout(user.getUserId());
            auditLogger.logout(user.getUsername());
        }
        return R.ok();
    }

    @GetMapping("/me")
    public R<CurrentUserVO> me() {
        return R.ok(authService.currentUser(UserContext.get()));
    }
}
