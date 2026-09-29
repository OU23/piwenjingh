package com.example.toolbox.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.excel.ExcelConstants;
import com.example.toolbox.excel.XlsxWriter;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.service.UserImportService;
import com.example.toolbox.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新建 / 导入账号默认权限（v1.14 / 计划 P11 T4）—— 业务语义层。
 *
 * <p>覆盖：A6 新建账号 {@code permissions=null} → 仅 {@code tool:download}；
 * A7 显式传 {@code []} → 无权限（尊重主动清空）；
 * A8 Excel 导入账号（复用 {@link UserService#create} 且从不设 permissions）→ 仅 {@code tool:download}。
 */
class UserDefaultPermissionTest extends IntegrationTestBase {

    @Autowired
    private UserService userService;
    @Autowired
    private UserImportService userImportService;

    private Long deptId;

    @BeforeEach
    void setUpData() {
        DeptDO dept = new DeptDO();
        dept.setName("默认权限部");
        dept.setStatus(StatusValue.ENABLED);
        dept.setCreateTime(LocalDateTime.now());
        deptMapper.insert(dept);
        deptId = dept.getId();
    }

    // ---------------- A6：新建 null → 仅 tool:download ----------------

    @Test
    void createWithNullPermissionsDefaultsToDownloadOnly() {
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername("u_null_perm");
        dto.setRealName("空权限");
        dto.setPassword("123456");
        dto.setDeptId(IdUtils.toStr(deptId));
        // 不设置 permissions（null）

        String id = userService.create(dto);
        List<String> perms = userService.getPermissions(Long.parseLong(id));

        assertEquals(List.of(PermissionCode.TOOL_DOWNLOAD), perms,
                "permissions 为 null 应默认仅 tool:download");
        assertTrue(perms.contains(PermissionCode.TOOL_DOWNLOAD), "应包含下载权限");
        assertEquals(1, perms.size(), "不应包含上传等其它权限");
    }

    // ---------------- A7：显式 [] → 无权限 ----------------

    @Test
    void createWithEmptyPermissionsMeansNoPermission() {
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername("u_empty_perm");
        dto.setRealName("空数组");
        dto.setPassword("123456");
        dto.setDeptId(IdUtils.toStr(deptId));
        dto.setPermissions(List.of()); // 显式清空

        String id = userService.create(dto);
        List<String> perms = userService.getPermissions(Long.parseLong(id));

        assertTrue(perms.isEmpty(), "显式传 [] 应表示无权限，默认逻辑不触发");
    }

    // ---------------- A8：导入账号 → 仅 tool:download ----------------

    @Test
    void importedUserDefaultsToDownloadOnly() {
        MockMultipartFile file = buildFile(List.of(
                List.of("imp_user", "导入用户", "默认权限部", "123456")));

        userImportService.importUsers(file);

        UserDO u = userMapper.selectOne(
                Wrappers.<UserDO>lambdaQuery().eq(UserDO::getUsername, "imp_user"));
        List<String> perms = userService.getPermissions(u.getId());

        assertEquals(List.of(PermissionCode.TOOL_DOWNLOAD), perms,
                "导入账号应自动获得仅 tool:download（复用 create 的 null 默认）");
    }

    private MockMultipartFile buildFile(List<List<String>> dataRows) {
        byte[] bytes = XlsxWriter.toBytes(ExcelConstants.USER_SHEET,
                XlsxWriter.headOf(ExcelConstants.USER_HEAD), dataRows);
        return new MockMultipartFile("file", "users.xlsx",
                ExcelConstants.XLSX_CONTENT_TYPE, bytes);
    }
}
