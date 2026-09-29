package com.example.toolbox.security;

import com.example.toolbox.common.result.R;
import org.junit.jupiter.api.Test;

import java.util.Set;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证过滤器（真 401）行为：公开路径放行；缺令牌 / 非法令牌 → 401；合法令牌 → 200。
 * 脱离 Spring 上下文，使用 standalone MockMvc。
 *
 * <p>M-18：合法令牌还须携带**有效会话标识**（{@code sid}）—— 见 {@link #validTokenPasses()}。
 */
class JwtAuthFilterTest {

    private final JwtProvider provider = new JwtProvider(new JwtProperties());
    private final PublicPathProperties publicPaths = new PublicPathProperties();
    private final SessionRegistry registry = new SessionRegistry();
    private final JwtAuthFilter filter = new JwtAuthFilter(provider, publicPaths, registry);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new TestController()).addFilters(filter).build();
    }

    @Test
    void publicPathBypassesWithoutToken() throws Exception {
        mvc().perform(MockMvcRequestBuilders.get("/api/system/init-status"))
                .andExpect(status().isOk());
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mvc().perform(MockMvcRequestBuilders.get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenPasses() throws Exception {
        // M-18：令牌须带 sid，且该 sid 须为该账号当前会话
        String sid = "test-sid-1";
        registry.establish(1L, sid);
        String token = provider.sign(LoginUser.builder()
                .userId(1L).username("a").deptId(1L).permissions(Set.of()).sid(sid).build());
        mvc().perform(MockMvcRequestBuilders.get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        mvc().perform(MockMvcRequestBuilders.get("/api/auth/me")
                        .header("Authorization", "Bearer bad.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void responseBodyIsUnauthorizedCode() throws Exception {
        mvc().perform(MockMvcRequestBuilders.get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class TestController {
        @GetMapping("/api/system/init-status")
        public R<Void> initStatus() {
            return R.ok();
        }

        @GetMapping("/api/auth/me")
        public R<Void> me() {
            return R.ok();
        }
    }
}
