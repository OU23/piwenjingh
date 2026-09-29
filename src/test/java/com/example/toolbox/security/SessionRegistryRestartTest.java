package com.example.toolbox.security;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话注册表「服务重启」行为（M-18 / 契约 §3.2 <b>v1.18</b>）。
 *
 * <p>覆盖验收 <b>A1 / A2 / A3 / A4</b>：
 * <ul>
 *   <li><b>A1</b>：服务重启（注册表清空）后，原令牌**一律 401**，**不得**被自动接管为当前会话；</li>
 *   <li><b>A2</b>：重启后重新登录可恢复（新令牌可用，旧令牌仍 401）；</li>
 *   <li><b>A3 ★</b>：注册表为空时**并发**请求 → **全部 401**（变更前因 A1 竞态会出现部分 200）；</li>
 *   <li><b>A4</b>：A1 入口（{@code registerIfAbsent} / {@code everRegistered}）已从类中移除。</li>
 * </ul>
 *
 * <p><b>防回归问题</b>：① 重启后旧令牌被静默接管（造成"用户看不见的登录态"）；
 * ② 并发下"部分 200 / 部分 401"，导致前端误清登录态、随后登录撞 1008。
 * <p><b>关键不变量</b>：注册表为空 ⇒ 任何令牌都不是当前会话 ⇒ 401 / 1001；会话只能由登录（establish）建立。
 */
@AutoConfigureMockMvc
class SessionRegistryRestartTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long deptId;
    private Long userId;

    @BeforeEach
    void setUpData() {
        DeptDO d = new DeptDO();
        d.setName("研发部");
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        deptId = d.getId();

        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername("u1");
        dto.setRealName("测试用户");
        dto.setPassword("pass123456");
        dto.setDeptId(String.valueOf(deptId));
        dto.setPermissions(List.of(PermissionCode.TOOL_DOWNLOAD));
        userId = Long.valueOf(userService.create(dto));
    }

    /** 签发一个「未过期、但未在注册表登记」的令牌（模拟服务重启前留下的令牌） */
    private String signStaleToken() {
        return jwtProvider.sign(LoginUser.builder()
                .userId(userId).username("u1").deptId(deptId)
                .permissions(Set.of(PermissionCode.TOOL_DOWNLOAD))
                .sid("stale-sid-before-restart")
                .build());
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        assertEquals(0, node.get("code").asInt(), "登录应成功");
        return node.get("data").get("token").asText();
    }

    /** A1 ★：服务重启（注册表清空）后，原令牌 → 401，**不**被自动接管 */
    @Test
    void staleTokenAfterRestartIsUnauthorized() throws Exception {
        String stale = signStaleToken();
        sessionRegistry.clear(); // 模拟服务重启

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + stale))
                .andExpect(status().isUnauthorized());
    }

    /** A2：重启后重新登录 → 新令牌可用；旧令牌仍 401（已被新会话替代） */
    @Test
    void reloginAfterRestartRestoresAccessAndKeepsStaleTokenInvalid() throws Exception {
        String stale = signStaleToken();
        sessionRegistry.clear(); // 模拟服务重启

        String fresh = loginAndGetToken("u1", "pass123456");

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + fresh))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + stale))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A3 ★（熔断守卫）：注册表为空时**并发**请求同一令牌 → **全部 401**。
     *
     * <p>变更前实现会在 A1 分支出现竞态（一个请求注册成功 → 200；另一个复查时注册表仍空 → 401），
     * 本用例断言"不存在任何 200"，即钉死该缺陷。
     */
    @Test
    void concurrentRequestsOnEmptyRegistryAreAllUnauthorized() throws Exception {
        String stale = signStaleToken();
        sessionRegistry.clear(); // 模拟服务重启

        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<Integer>> futures = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> mockMvc.perform(get("/api/auth/me")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + stale))
                        .andReturn().getResponse().getStatus()));
            }
            List<Integer> statuses = new ArrayList<>(concurrency);
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertTrue(statuses.stream().allMatch(s -> s == 401),
                    "注册表为空时并发请求必须全部 401（不得出现被接管的 200），实际：" + statuses);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A4：A1 入口已从类中移除（防止被重新引入） */
    @Test
    void registryNoLongerExposesAutoTakeoverApi() {
        assertTrue(Arrays.stream(SessionRegistry.class.getDeclaredMethods())
                        .noneMatch(m -> m.getName().equals("registerIfAbsent")),
                "SessionRegistry 不应再暴露 registerIfAbsent（A1 已随 v1.18 取消）");
        assertTrue(Arrays.stream(SessionRegistry.class.getDeclaredFields())
                        .noneMatch(f -> f.getName().equals("everRegistered")),
                "SessionRegistry 不应再有 everRegistered 字段（A1 已随 v1.18 取消）");
    }
}
