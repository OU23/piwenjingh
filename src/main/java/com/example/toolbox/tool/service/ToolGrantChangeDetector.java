package com.example.toolbox.tool.service;

import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.mapper.ToolMapper;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 定向授权的「集合变化」判定（契约 §3.3 v1.17）。
 *
 * <p>从 {@link ToolGrantService} 抽出（职责单一：**只算 diff，不落库**），
 * 既让授权服务聚焦写入，也避免其超过 300 行红线（principles 刹车 B）。
 *
 * <p><b>为什么必须基于「落库结果」比较</b>：v1.14 起，PUBLIC 工具 / 「工具部门 == 用户部门」这类
 * **无意义配对**会在保存时**静默丢弃**。若改用**入参**算 diff，就会给"其实什么都没变"的用户
 * 推送变更通知（熔断自检已坐实该失败）。
 *
 * <p>因此本类只把**有意义配对**纳入前后集合：这类配对的增删，才真正改变用户的"额外可下载工具"集合。
 */
@Component
@RequiredArgsConstructor
public class ToolGrantChangeDetector {

    private final ToolMapper toolMapper;
    private final UserMapper userMapper;

    /**
     * 用户侧：本次保存后，该用户的「额外可下载工具」集合是否**实际变化**。
     *
     * @param grantedToolIdsBefore 保存**前**该用户在 {@code tb_tool_grant} 中的工具 ID
     * @param userDeptId           该用户部门（用于排除"同部门本就可见"）
     * @param after                本次**实际落库**的工具 ID
     */
    public boolean userGrantsChanged(List<Long> grantedToolIdsBefore, Long userDeptId, List<Long> after) {
        return !meaningfulToolIds(grantedToolIdsBefore, userDeptId).equals(new HashSet<>(after));
    }

    /**
     * 工具侧：受影响用户 = **新增 ∪ 被移除**（对称差，只统计有意义配对）。
     *
     * <p>"本就在且仍在"的用户**不算受影响**（不应收到通知）。
     *
     * @param grantedUserIdsBefore 保存**前**该工具在 {@code tb_tool_grant} 中的用户 ID
     * @param toolDeptId           工具所属部门（用于排除"本部门人员"）
     * @param after                本次**实际落库**的用户 ID
     */
    public List<Long> affectedUsers(List<Long> grantedUserIdsBefore, Long toolDeptId, List<Long> after) {
        Set<Long> before = meaningfulUserIds(grantedUserIdsBefore, toolDeptId);
        Set<Long> now = new HashSet<>(after);

        Set<Long> affected = new HashSet<>(before);
        affected.addAll(now);
        Set<Long> unchanged = new HashSet<>(before);
        unchanged.retainAll(now);
        affected.removeAll(unchanged);

        return new ArrayList<>(affected);
    }

    // ---------------- 内部 ----------------

    /** 过滤出"有意义配对"的工具：排除 PUBLIC（全员本就可见）与"工具部门 == 用户部门" */
    private Set<Long> meaningfulToolIds(List<Long> toolIds, Long userDeptId) {
        Set<Long> result = new HashSet<>();
        if (toolIds == null) {
            return result;
        }
        for (Long toolId : toolIds) {
            ToolDO tool = toolMapper.selectById(toolId);
            if (tool == null || Scope.PUBLIC.equals(tool.getScope())) {
                continue;
            }
            if (userDeptId != null && userDeptId.equals(tool.getDeptId())) {
                continue;
            }
            result.add(toolId);
        }
        return result;
    }

    /** 过滤出"有意义配对"的用户：排除"用户部门 == 工具所属部门"（本部门本就可见） */
    private Set<Long> meaningfulUserIds(List<Long> userIds, Long toolDeptId) {
        Set<Long> result = new HashSet<>();
        if (userIds == null) {
            return result;
        }
        for (Long userId : userIds) {
            UserDO user = userMapper.selectById(userId);
            if (user == null) {
                continue;
            }
            if (user.getDeptId() != null && user.getDeptId().equals(toolDeptId)) {
                continue;
            }
            result.add(userId);
        }
        return result;
    }
}
