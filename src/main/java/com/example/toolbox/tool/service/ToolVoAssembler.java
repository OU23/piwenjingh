package com.example.toolbox.tool.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.util.DateTimeUtils;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.tag.entity.TagDO;
import com.example.toolbox.tag.mapper.TagMapper;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolTagDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.mapper.ToolTagMapper;
import com.example.toolbox.tool.vo.ToolDetailVO;
import com.example.toolbox.tool.vo.ToolVO;
import com.example.toolbox.tool.vo.ToolVersionVO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具 VO 组装（契约 §9.3）。
 *
 * <p>从 {@link ToolService} 拆出，职责单一：只做"实体 → 对外 VO"的转换与关联数据装配。
 *
 * <p><b>硬约束</b>：对外 VO **绝不包含 fileKey 等存储内部字段**（契约 §9.3 明确）。
 * 因此本类是"防泄漏"的最后一道关口 —— 新增字段前先确认它是否可以对外暴露。
 */
@Component
@RequiredArgsConstructor
public class ToolVoAssembler {

    private final ToolTagMapper toolTagMapper;
    private final TagMapper tagMapper;
    private final UserMapper userMapper;
    private final DeptMapper deptMapper;

    /** 列表项 VO（不含 fileKey） */
    public ToolVO toVO(ToolDO tool) {
        return ToolVO.builder()
                .id(IdUtils.toStr(tool.getId()))
                .name(tool.getName())
                .latestVersion(tool.getLatestVersion())
                .intro(tool.getIntro())
                .fileExt(tool.getFileExt())
                .scope(tool.getScope())
                .tags(loadTags(tool.getId()))
                .uploaderName(userName(tool.getUploaderId()))
                .deptName(deptName(tool.getDeptId()))
                .fileSize(tool.getFileSize())
                .downloadCount(tool.getDownloadCount())
                .updateTime(DateTimeUtils.format(tool.getUpdateTime()))
                .build();
    }

    /** 详情 VO = 列表项字段 + 详情扩展字段 + 版本列表 */
    public ToolDetailVO toDetailVO(ToolDO tool, List<ToolVersionDO> versions) {
        ToolVO base = toVO(tool);
        return ToolDetailVO.builder()
                .id(base.getId()).name(base.getName()).latestVersion(base.getLatestVersion())
                .intro(base.getIntro()).fileExt(base.getFileExt()).scope(base.getScope())
                .tags(base.getTags()).uploaderName(base.getUploaderName()).deptName(base.getDeptName())
                .fileSize(base.getFileSize()).downloadCount(base.getDownloadCount())
                .updateTime(base.getUpdateTime())
                .readme(tool.getReadme()).platform(tool.getPlatform()).dependency(tool.getDependency())
                .uploaderId(IdUtils.toStr(tool.getUploaderId())).deptId(IdUtils.toStr(tool.getDeptId()))
                .createTime(DateTimeUtils.format(tool.getCreateTime()))
                .versions(versions.stream()
                        .map(v -> toVersionVO(v, v.getId().equals(tool.getLatestVersionId()))).toList())
                .build();
    }

    /** 版本 VO（不含 fileKey） */
    public ToolVersionVO toVersionVO(ToolVersionDO v, boolean isLatest) {
        return ToolVersionVO.builder()
                .id(IdUtils.toStr(v.getId()))
                .version(v.getVersion())
                .fileName(v.getFileName())
                .fileExt(v.getFileExt())
                .fileSize(v.getFileSize())
                .uploaderName(userName(v.getUploaderId()))
                .uploadTime(DateTimeUtils.format(v.getCreateTime()))
                .downloadCount(v.getDownloadCount())
                .isLatest(isLatest)
                .build();
    }

    // ---------------- 关联数据 ----------------

    /** 工具标签（`{ id, name }[]`） */
    public List<ToolVO.TagItem> loadTags(Long toolId) {
        List<ToolTagDO> rels = toolTagMapper.selectList(
                Wrappers.<ToolTagDO>lambdaQuery().eq(ToolTagDO::getToolId, toolId));
        if (rels.isEmpty()) {
            return List.of();
        }
        List<Long> tagIds = rels.stream().map(ToolTagDO::getTagId).toList();
        List<TagDO> tags = tagMapper.selectBatchIds(tagIds);
        List<ToolVO.TagItem> items = new ArrayList<>();
        for (TagDO t : tags) {
            items.add(ToolVO.TagItem.builder()
                    .id(IdUtils.toStr(t.getId())).name(t.getName()).build());
        }
        return items;
    }

    /** 上传人姓名 */
    public String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        UserDO u = userMapper.selectById(userId);
        return u == null ? null : u.getRealName();
    }

    /** 归属部门名称 */
    public String deptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        DeptDO d = deptMapper.selectById(deptId);
        return d == null ? null : d.getName();
    }
}
