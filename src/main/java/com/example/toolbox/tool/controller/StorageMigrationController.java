package com.example.toolbox.tool.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.result.R;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.RequiresPermission;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.storage.MigrationTaskVO;
import com.example.toolbox.storage.StorageMigrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存量迁移（契约 §9.9 / v1.19，M-22）。
 *
 * <p>权限 {@code sys:setting}（**仅全局管理员**）。
 * <p><b>可替换性</b>：本期同步（返回即 COMPLETED）；将来异步时本控制器**一行不改**
 * —— 前端先判 {@code status}：非完成态再轮询查询接口即可。
 */
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class StorageMigrationController {

    private final StorageMigrationService migrationService;

    /** 发起存量迁移（可选 limit 限制单次条数，便于分批 / 可观测） */
    @PostMapping("/migrate-to-oss")
    @RequiresPermission(PermissionCode.SYS_SETTING)
    public R<MigrationTaskVO> start(@RequestParam(required = false) Integer limit) {
        LoginUser user = UserContext.get();
        String operator = user == null ? null : user.getUsername();
        return R.ok(migrationService.start(limit, operator));
    }

    /** 查询迁移任务进度 / 结果 */
    @GetMapping("/migrate-to-oss/{taskId}")
    @RequiresPermission(PermissionCode.SYS_SETTING)
    public R<MigrationTaskVO> status(@PathVariable String taskId) {
        return R.ok(migrationService.status(taskId));
    }
}
