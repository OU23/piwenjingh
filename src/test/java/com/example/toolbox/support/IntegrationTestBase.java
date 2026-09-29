package com.example.toolbox.support;

import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.SessionRegistry;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.setting.mapper.SettingMapper;
import com.example.toolbox.tag.mapper.TagMapper;
import com.example.toolbox.tool.mapper.ToolGrantMapper;
import com.example.toolbox.tool.mapper.ToolMapper;
import com.example.toolbox.tool.mapper.ToolTagMapper;
import com.example.toolbox.tool.mapper.ToolVersionMapper;
import com.example.toolbox.user.mapper.UserMapper;
import com.example.toolbox.user.mapper.UserPermissionMapper;
import com.example.toolbox.ws.WsSessionStore;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 集成测试基类：H2 内存库 + 真实 Spring 上下文。
 *
 * <p>夹具自清理（testing-rules：集成测试必须自清理，避免污染后续用例 / 人工验收数据）。
 * **不使用** `@Transactional` 回滚——用例要验证真实提交后的可见效果，
 * 因此改为 {@code @AfterEach} 按依赖倒序物理清表。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired
    protected UserMapper userMapper;
    @Autowired
    protected DeptMapper deptMapper;
    @Autowired
    protected UserPermissionMapper userPermissionMapper;
    @Autowired
    protected TagMapper tagMapper;
    @Autowired
    protected SettingMapper settingMapper;
    @Autowired
    protected ToolMapper toolMapper;
    @Autowired
    protected ToolVersionMapper toolVersionMapper;
    @Autowired
    protected ToolTagMapper toolTagMapper;
    @Autowired
    protected ToolGrantMapper toolGrantMapper;
    /** 单账号单登录会话注册表（M-18）：单例状态，必须随用例清理 */
    @Autowired
    protected SessionRegistry sessionRegistry;
    /** WebSocket 连接注册表（M-18）：单例状态，必须随用例清理 */
    @Autowired
    protected WsSessionStore wsSessionStore;
    @Autowired
    protected JwtProvider jwtProvider;

    @AfterEach
    void cleanUp() {
        // 倒序清理：先子表后主表（无外键，仍按语义顺序，避免脏数据影响下一用例）
        toolTagMapper.delete(null);
        toolVersionMapper.delete(null);
        toolGrantMapper.delete(null);
        toolMapper.delete(null);
        userPermissionMapper.delete(null);
        userMapper.delete(null);
        deptMapper.delete(null);
        tagMapper.delete(null);
        settingMapper.delete(null);
        UserContext.clear();
        sessionRegistry.clear();
        wsSessionStore.clear();
    }

    /**
     * 造一个「当前会话有效」的令牌（M-18）：自动补 {@code sid} 并在 {@link SessionRegistry}
     * 登记为当前会话。接口层用例（MockMvc / 真实 HTTP）应改用本方法，
     * 否则令牌会因缺少有效会话被过滤器判为 401。
     */
    protected String signToken(LoginUser user) {
        String sid = "test-sid-" + user.getUserId();
        user.setSid(sid);
        sessionRegistry.establish(user.getUserId(), sid);
        return jwtProvider.sign(user);
    }

    /**
     * 构造登录上下文（供被测 Service 内部取当前用户 / 审计日志用）。
     * M-18 起同时登记该用户的当前会话（sid），使依赖会话的链路（如下载令牌）可用。
     */
    protected void loginAs(Long userId, String username, Long deptId, String... permissions) {
        String sid = "test-sid-" + userId;
        UserContext.set(LoginUser.builder()
                .userId(userId)
                .username(username)
                .deptId(deptId)
                .permissions(java.util.Set.of(permissions))
                .sid(sid)
                .build());
        sessionRegistry.establish(userId, sid);
    }
}
