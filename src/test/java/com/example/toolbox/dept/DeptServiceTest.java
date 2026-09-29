package com.example.toolbox.dept;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.dto.DeptStatusDTO;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.dept.vo.DeptVO;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门模块（验收 A4 / C2 / C4）。
 * 覆盖：列表全量与状态筛选、新建/编辑、重名 1005、有用户时停用 1005（含人数）、
 * 不存在的部门 ID → 1004、幂等停用。
 */
class DeptServiceTest extends IntegrationTestBase {

    @Autowired
    private DeptService deptService;
    @Autowired
    private UserService userService;

    @BeforeEach
    void setUpOperator() {
        // 部门写操作走 @RequiresPermission 前的 Service 调用，需有登录上下文供审计取操作人；
        // 操作人 ID 用极大值，避免与 H2 自增主键（从 1 开始）碰撞而误触"不能停用自身"守卫
        loginAs(999_999L, "admin", 1L, PermissionCode.DEPT_MANAGE, PermissionCode.USER_MANAGE);
    }

    private DeptSaveDTO saveDto(String name, String remark) {
        DeptSaveDTO dto = new DeptSaveDTO();
        dto.setName(name);
        dto.setRemark(remark);
        return dto;
    }

    private DeptStatusDTO statusDto(int status) {
        DeptStatusDTO dto = new DeptStatusDTO();
        dto.setStatus(status);
        return dto;
    }

    @Test
    void createListAndFilterByStatus() {
        String aId = deptService.create(saveDto("研发部", "负责产品研发"));
        String bId = deptService.create(saveDto("市场部", null));
        assertNotNull(aId);
        assertNotNull(bId);

        List<DeptVO> all = deptService.list(null);
        assertEquals(2, all.size(), "列表应全量返回两个部门");

        // 默认新建即为启用；停用其一后按状态筛选
        deptService.updateStatus(Long.valueOf(bId), statusDto(0));
        List<DeptVO> enabled = deptService.list(1);
        assertEquals(1, enabled.size(), "按启用筛选应只剩 1 个");
        assertEquals("研发部", enabled.get(0).getName());

        List<DeptVO> disabled = deptService.list(0);
        assertEquals(1, disabled.size(), "按停用筛选应有 1 个");
        assertEquals("市场部", disabled.get(0).getName());
    }

    @Test
    void listIncludesUserCount() {
        String deptId = deptService.create(saveDto("研发部", null));
        assertEquals(0L, deptService.list(null).get(0).getUserCount(), "新部门人数应为 0");

        UserSaveDTO u = new UserSaveDTO();
        u.setUsername("zhangsan");
        u.setRealName("张三");
        u.setPassword("pass123456");
        u.setDeptId(deptId);
        userService.create(u);

        assertEquals(1L, deptService.list(null).get(0).getUserCount(), "部门人数应随建号更新");
    }

    /** A4：重名 → 1005 */
    @Test
    void duplicateNameRejected() {
        deptService.create(saveDto("研发部", null));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deptService.create(saveDto("研发部", "重名")));
        assertEquals(Code.CONFLICT, ex.getCode(), "重名应返回 1005");
    }

    @Test
    void updateSucceedsAndRenamingToExistingRejected() {
        String aId = deptService.create(saveDto("研发部", null));
        Long a = Long.valueOf(aId);
        deptService.create(saveDto("市场部", null));

        // 正常编辑：改名 + 改备注
        deptService.update(a, saveDto("研发一部", "更名"));
        DeptVO updated = deptService.list(null).stream()
                .filter(d -> d.getName().equals("研发一部")).findFirst().orElseThrow();
        assertEquals("更名", updated.getRemark());

        // 改名撞已有名称 → 1005
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deptService.update(a, saveDto("市场部", null)));
        assertEquals(Code.CONFLICT, ex.getCode());

        // 名称未变时提交同名（等于自己）不应被误判为重名
        deptService.update(a, saveDto("研发一部", "再存一次"));
    }

    /** A4 / C2：有用户时停用 → 1005 且说明人数 */
    @Test
    void disableDeptWithUsersRejectedWithCountInMessage() {
        String deptId = deptService.create(saveDto("研发部", null));
        UserSaveDTO u = new UserSaveDTO();
        u.setUsername("zhangsan");
        u.setRealName("张三");
        u.setPassword("pass123456");
        u.setDeptId(deptId);
        userService.create(u);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> deptService.updateStatus(Long.valueOf(deptId), statusDto(0)));
        assertEquals(Code.CONFLICT, ex.getCode(), "有用户时停用应返回 1005");
        assertTrue(ex.getMessage().contains("1"),
                "提示应含人数，实际：" + ex.getMessage());
    }

    @Test
    void disableEmptyDeptSucceedsAndIsIdempotent() {
        String deptId = deptService.create(saveDto("研发部", null));
        Long id = Long.valueOf(deptId);

        deptService.updateStatus(id, statusDto(0));
        assertEquals(1, deptService.list(0).size(), "空部门应可停用");

        // 幂等：再停用一次不报错，数据不重复
        deptService.updateStatus(id, statusDto(0));
        assertEquals(1, deptService.list(0).size(), "重复停用不应产生异常或多余数据");

        // 可再启用
        deptService.updateStatus(id, statusDto(1));
        assertEquals(1, deptService.list(1).size(), "应可重新启用");
    }

    @Test
    void invalidStatusRejected() {
        String deptId = deptService.create(saveDto("研发部", null));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deptService.updateStatus(Long.valueOf(deptId), statusDto(9)));
        assertEquals(Code.PARAM_INVALID, ex.getCode(), "非法状态应返回 1003");
    }

    /** C4：不存在的部门 ID → 1004（不是 500） */
    @Test
    void nonExistentDeptReturnsNotFound() {
        BusinessException onUpdate = assertThrows(BusinessException.class,
                () -> deptService.update(999999L, saveDto("不存在", null)));
        assertEquals(Code.NOT_FOUND, onUpdate.getCode(), "编辑不存在部门应返回 1004");

        BusinessException onStatus = assertThrows(BusinessException.class,
                () -> deptService.updateStatus(999999L, statusDto(0)));
        assertEquals(Code.NOT_FOUND, onStatus.getCode(), "停用不存在部门应返回 1004");
    }
}
