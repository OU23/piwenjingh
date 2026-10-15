package com.example.toolbox.setting;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.SettingKey;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.setting.service.SettingService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分片大小设置项 `chunkSize`（B14 验收 A16）。
 *
 * <p>锁死：① 读取容错（缺失 / 非数字 / 越界 → 回落 20 MB）；
 * ② 写入区间校验（5 ~ 100 MB，越界 → 1003 且不落库）；③ `SettingVO` 返回该字段；
 * ④ "至少传一项"规则**不变**。
 */
@AutoConfigureMockMvc
class ChunkSizeSettingTest extends IntegrationTestBase {

    private static final long MB = 1024L * 1024L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SettingService settingService;

    private Long deptId;

    @BeforeEach
    void setUpDept() {
        DeptDO dept = new DeptDO();
        dept.setName("研发部");
        dept.setStatus(StatusValue.ENABLED);
        dept.setCreateTime(LocalDateTime.now());
        deptMapper.insert(dept);
        deptId = dept.getId();
    }

    // ---------------- 读取容错 ----------------

    @Test
    void missingValueFallsBackToDefault() {
        assertEquals(20 * MB, settingService.getChunkSize(), "缺失 → 默认 20 MB");
        assertEquals(SettingService.DEFAULT_CHUNK_SIZE, settingService.getChunkSize());
    }

    @Test
    void nonNumericValueFallsBackToDefault() {
        settingService.putValue(SettingKey.CHUNK_SIZE, "二十");

        assertEquals(SettingService.DEFAULT_CHUNK_SIZE, settingService.getChunkSize());
    }

    @Test
    void outOfRangeStoredValueFallsBackToDefault() {
        settingService.putValue(SettingKey.CHUNK_SIZE, String.valueOf(1024));  // 1 KB，低于下限

        assertEquals(SettingService.DEFAULT_CHUNK_SIZE, settingService.getChunkSize());
    }

    @Test
    void storedValueWithinRangeIsReturned() {
        settingService.putValue(SettingKey.CHUNK_SIZE, String.valueOf(50 * MB));

        assertEquals(50 * MB, settingService.getChunkSize());
    }

    // ---------------- 接口读写 ----------------

    @Test
    void settingsReadReturnsChunkSize() throws Exception {
        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunkSize").value(20971520));
    }

    @Test
    void saveWithinRangeTakesEffect() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"chunkSize\":" + (50 * MB) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertEquals(50 * MB, settingService.getChunkSize());
    }

    @Test
    void saveBelowMinIsRejectedWith1003() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"chunkSize\":5242879}")) // 5 MB - 1
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        assertEquals(SettingService.DEFAULT_CHUNK_SIZE, settingService.getChunkSize(), "越界不得写入");
    }

    @Test
    void saveAboveMaxIsRejectedWith1003() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"chunkSize\":104857601}")) // 100 MB + 1
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        assertEquals(SettingService.DEFAULT_CHUNK_SIZE, settingService.getChunkSize(), "越界不得写入");
    }

    /** "至少传一项"规则不变：三项都不传 → 1003，不静默成功 */
    @Test
    void emptyPayloadStillRejectedWith1003() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ---------------- 内部 ----------------

    private String adminToken() {
        UserDO u = new UserDO();
        u.setUsername("admin");
        u.setPassword("$2a$10$placeholder-shape-valid");
        u.setRealName("管理员");
        u.setDeptId(deptId);
        u.setStatus(StatusValue.ENABLED);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);
        UserPermissionDO up = new UserPermissionDO();
        up.setUserId(u.getId());
        up.setPermissionCode(PermissionCode.SYS_SETTING);
        up.setCreateTime(LocalDateTime.now());
        userPermissionMapper.insert(up);

        return signToken(LoginUser.builder()
                .userId(u.getId()).username(u.getUsername()).realName(u.getRealName())
                .deptId(u.getDeptId()).permissions(Set.of(PermissionCode.SYS_SETTING)).build());
    }
}
