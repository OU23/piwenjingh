package com.example.toolbox.system.controller;

import com.example.toolbox.common.result.R;
import com.example.toolbox.system.dto.InitDTO;
import com.example.toolbox.system.service.SystemService;
import com.example.toolbox.system.vo.InitStatusVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统初始化（契约 §十一 /api/system）。两个接口均为公开（见 PublicPathProperties 白名单）。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemService systemService;

    @GetMapping("/init-status")
    public R<InitStatusVO> initStatus() {
        return R.ok(new InitStatusVO(systemService.isInitialized()));
    }

    @PostMapping("/init")
    public R<Void> init(@RequestBody @Valid InitDTO dto) {
        systemService.init(dto);
        return R.ok();
    }
}
