package com.example.toolbox.tag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.DateTimeUtils;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.tag.dto.TagSaveDTO;
import com.example.toolbox.tag.entity.TagDO;
import com.example.toolbox.tag.mapper.TagMapper;
import com.example.toolbox.tag.vo.TagVO;
import com.example.toolbox.tool.entity.ToolTagDO;
import com.example.toolbox.tool.mapper.ToolTagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标签服务（契约 §七）：列表 / 新增 / 编辑 / 删除。
 *
 * <p>约束：重名 → 1005；**删除标签只解除与工具的关联，不删除工具**。
 */
@Service
@RequiredArgsConstructor
public class TagService {

    private final TagMapper tagMapper;
    private final ToolTagMapper toolTagMapper;
    private final AuditLogger auditLogger;

    /** 标签列表（全量，供筛选与勾选；支持关键词） */
    public List<TagVO> list(String keyword) {
        var w = Wrappers.<TagDO>lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            w.like(TagDO::getName, keyword.trim());
        }
        w.orderByAsc(TagDO::getId);
        return tagMapper.selectList(w).stream()
                .map(t -> TagVO.builder()
                        .id(IdUtils.toStr(t.getId()))
                        .name(t.getName())
                        .toolCount(countTools(t.getId()))
                        .createTime(DateTimeUtils.format(t.getCreateTime()))
                        .build())
                .toList();
    }

    @Transactional
    public String create(TagSaveDTO dto) {
        String name = dto.getName().trim();
        if (nameExists(name)) {
            throw new BusinessException(Code.CONFLICT, "标签名称已存在");
        }
        TagDO tag = new TagDO();
        tag.setName(name);
        tag.setCreateTime(LocalDateTime.now());
        tagMapper.insert(tag);
        auditLogger.write(currentOperator(), "TAG_CREATE", "tag:" + tag.getId(), "OK");
        return IdUtils.toStr(tag.getId());
    }

    @Transactional
    public void update(Long id, TagSaveDTO dto) {
        TagDO tag = requireTag(id);
        String name = dto.getName().trim();
        if (!name.equals(tag.getName()) && nameExists(name)) {
            throw new BusinessException(Code.CONFLICT, "标签名称已存在");
        }
        tag.setName(name);
        tag.setUpdateTime(LocalDateTime.now());
        tagMapper.updateById(tag);
        auditLogger.write(currentOperator(), "TAG_UPDATE", "tag:" + id, "OK");
    }

    /**
     * 删除标签：**只解除与工具的关联，不删除工具**（契约 §七）。
     * 关联解除与标签删除在同一事务内完成。
     */
    @Transactional
    public void delete(Long id) {
        requireTag(id);
        toolTagMapper.delete(Wrappers.<ToolTagDO>lambdaQuery().eq(ToolTagDO::getTagId, id));
        tagMapper.deleteById(id);
        auditLogger.write(currentOperator(), "TAG_DELETE", "tag:" + id, "OK");
    }

    // ---------------- 内部 ----------------

    /** 标签关联的工具数（供列表展示） */
    public long countTools(Long tagId) {
        return toolTagMapper.selectCount(
                Wrappers.<ToolTagDO>lambdaQuery().eq(ToolTagDO::getTagId, tagId));
    }

    private TagDO requireTag(Long id) {
        TagDO tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException(Code.NOT_FOUND, "标签不存在");
        }
        return tag;
    }

    private boolean nameExists(String name) {
        return tagMapper.selectCount(
                Wrappers.<TagDO>lambdaQuery().eq(TagDO::getName, name)) > 0;
    }

    private String currentOperator() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getUsername();
    }
}
