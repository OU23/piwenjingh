package com.example.toolbox.auth.service;

import com.example.toolbox.auth.vo.CurrentUserVO;
import com.example.toolbox.auth.vo.LoginVO;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.SessionRegistry;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.service.UserService;
import com.example.toolbox.ws.WsCloseCode;
import com.example.toolbox.ws.WsMessages;
import com.example.toolbox.ws.WsSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 认证服务（契约 §三）：登录校验 + JWT 签发 + 当前用户信息。
 * 安全口径：账号不存在 / 密码错 / 账号停用**统一返回 1006**，不区分，避免账号探测。
 *
 * <p>单账号单登录（M-18 / 契约 §3.2）：登录成功即建立会话（{@code userId → sid}），
 * 令牌内嵌 {@code sid}；同账号已有活跃会话且未请求强制登录时返回 {@code 1008}
 * （该码**仅**用于登录接口的"需确认"分支）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserService userService;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogger auditLogger;
    private final SessionRegistry sessionRegistry;
    private final WsSessionStore wsSessionStore;

    public LoginVO login(String username, String password, Boolean force) {
        UserDO u = userService.findByUsername(username);
        // 三种失败情形合并为同一错误码，防止通过返回码探测账号是否存在
        if (u == null || !isEnabled(u) || !passwordEncoder.matches(password, u.getPassword())) {
            auditLogger.loginFail(username);
            throw new BusinessException(Code.BAD_CREDENTIALS);
        }
        // 单账号单登录：密码正确的前提下，已有活跃会话且未请求强制登录 → 1008。
        // force 不得绕过密码 / 停用校验 —— 上面已先行校验，此处只决定"是否踢掉旧会话"。
        if (sessionRegistry.hasActive(u.getId()) && !Boolean.TRUE.equals(force)) {
            throw new BusinessException(Code.FORCE_LOGIN_REQUIRED);
        }
        auditLogger.loginSuccess(username);
        LoginUser loginUser = toLoginUser(u);
        // 建立 / 替换会话：后写覆盖 → 旧会话立刻失效（旧令牌下一请求即 401）
        String sid = newSid();
        sessionRegistry.establish(u.getId(), sid);
        loginUser.setSid(sid);
        // 先通知旧会话下线，再签发/返回（把"两端同时在线"的窗口压到最小）。
        // 推送失败不得影响本次登录 —— 旧会话另有 401 / 4002 双保险（方案 §5.2 第 6 步）。
        notifyPreviousSession(u.getId());
        String token = jwtProvider.sign(loginUser);
        return LoginVO.builder()
                .token(token)
                .expiresIn(jwtProvider.getExpiresInSeconds())
                .user(toCurrentUserVO(loginUser))
                .build();
    }

    /** 当前登录用户信息（由 JwtAuthFilter 写入 UserContext，此处只做展示转换） */
    public CurrentUserVO currentUser(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        return toCurrentUserVO(loginUser);
    }

    /** 组装登录上下文：权限点实时从库读取（改权限即刻生效，不依赖旧令牌） */
    private LoginUser toLoginUser(UserDO u) {
        return LoginUser.builder()
                .userId(u.getId())
                .username(u.getUsername())
                .realName(u.getRealName())
                .deptId(u.getDeptId())
                .deptName(userService.deptName(u.getDeptId()))
                .permissions(userService.loadPermissionSet(u.getId()))
                .build();
    }

    private CurrentUserVO toCurrentUserVO(LoginUser u) {
        List<String> perms = new ArrayList<>(u.getPermissions());
        return CurrentUserVO.builder()
                .userId(IdUtils.toStr(u.getUserId()))
                .username(u.getUsername())
                .realName(u.getRealName())
                .deptId(IdUtils.toStr(u.getDeptId()))
                .deptName(u.getDeptName())
                .permissions(perms)
                .build();
    }

    /** 生成会话标识（sid）：32 位十六进制随机串 */
    private String newSid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 推送下线通知并关闭旧连接；任何异常都吞掉（不能因推送失败导致登录失败） */
    private void notifyPreviousSession(Long userId) {
        try {
            wsSessionStore.sendAndClose(userId, WsMessages.forceLogout(),
                    WsCloseCode.SESSION_REPLACED, "session replaced");
        } catch (Exception e) {
            log.warn("推送下线通知失败（已忽略），userId={}：{}", userId, e.getMessage());
        }
    }

    private boolean isEnabled(UserDO u) {
        return u.getStatus() != null && u.getStatus() == StatusValue.ENABLED;
    }
}
