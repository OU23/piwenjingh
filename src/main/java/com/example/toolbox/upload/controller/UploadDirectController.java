package com.example.toolbox.upload.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.result.R;
import com.example.toolbox.security.RequiresPermission;
import com.example.toolbox.upload.dto.UploadDirectCompleteDTO;
import com.example.toolbox.upload.dto.UploadDirectCredentialDTO;
import com.example.toolbox.upload.service.UploadDirectService;
import com.example.toolbox.upload.vo.UploadCredentialVO;
import com.example.toolbox.upload.vo.UploadMergeVO;
import com.example.toolbox.upload.vo.UploadModeVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OSS 直传（契约 v1.20 §8.5 ~ §8.7，M-23）。
 *
 * <p>三个端点均为 `tool:upload` 权限（由注解拦截器统一判权：未登录 → 401，无权限 → 403）；
 * **鉴权先于凭证签发**，任一情形都不得签发凭证。
 *
 * <p>⚠️ 直传**不使用** §8.1 的 `check` / `chunk` / `merge` 那组接口 —— 那组仅 `local` 模式适用。
 */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadDirectController {

    private final UploadDirectService uploadDirectService;

    /** 上传模式与分片参数（前端进入上传页第一步调用，据此分流） */
    @GetMapping("/mode")
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<UploadModeVO> mode() {
        return R.ok(uploadDirectService.mode());
    }

    /** 签发直传临时凭证（STS）+ 分配 / 复用对象键 */
    @PostMapping("/direct/credentials")
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<UploadCredentialVO> credentials(@RequestBody @Valid UploadDirectCredentialDTO dto) {
        return R.ok(uploadDirectService.credentials(dto));
    }

    /** 直传完成登记复核（HEAD + 返回 fileKey，出参同 merge） */
    @PostMapping("/direct/complete")
    @RequiresPermission(PermissionCode.TOOL_UPLOAD)
    public R<UploadMergeVO> complete(@RequestBody @Valid UploadDirectCompleteDTO dto) {
        return R.ok(uploadDirectService.complete(dto));
    }
}
