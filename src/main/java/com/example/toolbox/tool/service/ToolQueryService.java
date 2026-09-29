package com.example.toolbox.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.result.PageResult;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolTagDO;
import com.example.toolbox.tool.mapper.ToolMapper;
import com.example.toolbox.tool.mapper.ToolTagMapper;
import com.example.toolbox.tool.vo.ToolVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工具列表与检索（契约 §9.1 / §9.2 / §9.3）。
 *
 * <p>★ 本类的过滤条件**必须**经 {@link ToolService#applyVisibility} 构造（R3），
 * 不得在此重写"本部门 + 公共"的判定。
 *
 * <p>可见范围口径（security.md + N-04）：
 * <ul>
 *   <li>默认只返回"本部门 + PUBLIC"；</li>
 *   <li>{@code scope} 筛选参数**只能收窄、不能扩大** —— 传 PUBLIC 时仍受"本部门 + 公共"约束；</li>
 *   <li>全局管理员（持 sys:setting）**不受部门隔离**，可跨部门查看。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ToolQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private static final String SORT_CREATE_TIME = "createTime";
    private static final String SORT_DOWNLOAD_COUNT = "downloadCount";
    private static final String SORT_NAME = "name";

    private final ToolMapper toolMapper;
    private final ToolTagMapper toolTagMapper;
    private final ToolService toolService;

    /** 工具列表：检索 + 筛选 + 排序 + 分页 */
    public PageResult<ToolVO> page(int pageNum, int pageSize, String keyword,
                                   String tagIds, String fileExt, String scope,
                                   String sortBy, String sortOrder) {
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }

        int pn = pageNum <= 0 ? 1 : pageNum;
        int ps = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<ToolDO> w = Wrappers.lambdaQuery();

        // 排除已删除（列表不含已删除数据）
        w.eq(ToolDO::getDeleted, ToolService.NOT_DELETED);

        // ★ 可见范围过滤（含 M-17 定向授权例外）：唯一入口，不在此重写
        toolService.applyVisibilityIncludingGrant(w, ToolDO::getScope, ToolDO::getDeptId, user);

        // 关键词：匹配工具名称 + 简介（模糊）
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(ToolDO::getName, kw).or().like(ToolDO::getIntro, kw));
        }

        // 文件格式：多值逗号分隔，命中任一（存储统一小写）
        List<String> exts = splitLower(fileExt);
        if (!exts.isEmpty()) {
            w.in(ToolDO::getFileExt, exts);
        }

        // 可见范围筛选：只能收窄，不能扩大 —— 因此与上面的可见范围条件是"与"关系
        if (scope != null && !scope.isBlank()) {
            String s = scope.trim().toUpperCase();
            if (!Scope.DEPT.equals(s) && !Scope.PUBLIC.equals(s)) {
                throw new BusinessException(Code.PARAM_INVALID, "可见范围仅支持 DEPT 或 PUBLIC");
            }
            w.eq(ToolDO::getScope, s);
        }

        // 标签：多值逗号分隔，命中任一（先查关联表拿工具 ID，再作为 in 条件）
        List<Long> tagIdList = parseIds(tagIds);
        if (!tagIdList.isEmpty()) {
            List<Long> toolIds = toolTagMapper.selectList(
                            Wrappers.<ToolTagDO>lambdaQuery().in(ToolTagDO::getTagId, tagIdList))
                    .stream().map(ToolTagDO::getToolId).distinct().toList();
            if (toolIds.isEmpty()) {
                // 命中 0 个工具：直接返回空页，避免用 in () 触发 SQL 语法问题
                return PageResult.of(List.of(), 0L, pn, ps);
            }
            w.in(ToolDO::getId, toolIds);
        }

        applySort(w, sortBy, sortOrder);

        Page<ToolDO> page = toolMapper.selectPage(new Page<>(pn, ps), w);
        List<ToolVO> list = page.getRecords().stream().map(toolService::toVO).toList();
        return PageResult.of(list, page.getTotal(), pn, ps);
    }

    // ---------------- 内部 ----------------

    /** 排序：默认 createTime + desc；非法值回落默认（契约 §13.4） */
    private void applySort(LambdaQueryWrapper<ToolDO> w, String sortBy, String sortOrder) {
        String by = (sortBy == null || sortBy.isBlank()) ? SORT_CREATE_TIME : sortBy.trim();
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        switch (by) {
            case SORT_CREATE_TIME -> w.orderBy(true, asc, ToolDO::getCreateTime);
            case SORT_DOWNLOAD_COUNT -> w.orderBy(true, asc, ToolDO::getDownloadCount);
            case SORT_NAME -> w.orderBy(true, asc, ToolDO::getName);
            default -> w.orderByDesc(ToolDO::getCreateTime);
        }
        // 稳定分页：同值再按 id 兜底排序，避免翻页时记录重复/丢失
        w.orderByDesc(ToolDO::getId);
    }

    private List<String> splitLower(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    String v = s.toLowerCase();
                    while (v.startsWith(".")) {
                        v = v.substring(1);
                    }
                    return v;
                })
                .distinct()
                .toList();
    }

    private List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> IdUtils.parse(s, "标签ID"))
                .distinct()
                .toList();
    }
}
