package com.example.toolbox.tag.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.result.R;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.RequiresPermission;
import com.example.toolbox.tag.dto.TagSaveDTO;
import com.example.toolbox.tag.service.TagService;
import com.example.toolbox.tag.vo.TagVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签（契约 §七 /api/tags）。列表登录即可，写操作需 tag:manage。
 */
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    /** 标签列表（全量，供筛选与勾选） */
    @GetMapping
    public R<List<TagVO>> list(@RequestParam(required = false) String keyword) {
        return R.ok(tagService.list(keyword));
    }

    @PostMapping
    @RequiresPermission(PermissionCode.TAG_MANAGE)
    public R<String> create(@RequestBody @Valid TagSaveDTO dto) {
        return R.ok(tagService.create(dto));
    }

    @PutMapping("/{id}")
    @RequiresPermission(PermissionCode.TAG_MANAGE)
    public R<Void> update(@PathVariable String id, @RequestBody @Valid TagSaveDTO dto) {
        tagService.update(IdUtils.parse(id, "标签ID"), dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(PermissionCode.TAG_MANAGE)
    public R<Void> delete(@PathVariable String id) {
        tagService.delete(IdUtils.parse(id, "标签ID"));
        return R.ok();
    }
}
