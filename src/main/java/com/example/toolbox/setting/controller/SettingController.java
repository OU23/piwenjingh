package com.example.toolbox.setting.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.result.R;
import com.example.toolbox.security.RequiresPermission;
import com.example.toolbox.setting.dto.SettingSaveDTO;
import com.example.toolbox.setting.service.SettingAdminService;
import com.example.toolbox.setting.vo.SettingVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局设置（契约 §十 `/api/settings`）。
 *
 * <p>`GET` **登录即可**（上传页需要读单文件上限做预校验），但 `storageRoot` 只对
 * `sys:setting` 权限者返回（N-03）；`PUT` 需 `sys:setting`（由注解拦截器统一判权，403）。
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingController {

    private final SettingAdminService settingAdminService;

    /** 读取全局设置 */
    @GetMapping
    public R<SettingVO> get() {
        return R.ok(settingAdminService.get());
    }

    /** 保存全局设置（支持只提交变更项） */
    @PutMapping
    @RequiresPermission(PermissionCode.SYS_SETTING)
    public R<Void> save(@RequestBody SettingSaveDTO dto) {
        settingAdminService.save(dto);
        return R.ok();
    }
}
