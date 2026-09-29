package com.example.toolbox.tool.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolGrantDO;
import com.example.toolbox.tool.mapper.ToolGrantMapper;
import com.example.toolbox.tool.mapper.ToolMapper;
import com.example.toolbox.tool.vo.ToolBriefVO;
import com.example.toolbox.tool.vo.UserBriefVO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.mapper.UserMapper;
import com.example.toolbox.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具定向授权（M-17，契约 §6.1 / §9.8）。
 *
 * <p>用户侧（{@code /api/users/{id}/tool-grants}）与工具侧（{@code /api/tools/{id}/grants}）
 * 操<b>同一张</b> {@code tb_tool_grant}，故本服务内部统一落库，两端仅入口不同、语义完全一致。
 *
 * <p>关键口径：
 * <ul>
 *   <li><b>不做可见范围校验</b>：持 {@code user:grant} 即可对<b>任意部门</b>的工具 / 用户授权
 *       （跨部门授权正是本能力目的，叠加部门隔离会使非全局管理员无法授权）；</li>
 *   <li><b>全量覆盖</b>：先删后插、单事务；空数组 = 清空；重复 ID 自动去重；</li>
 *   <li><b>不部分成功</b>：任一目标 ID 不存在 / 已彻底删除 → {@code 1004} 并指出无效 ID，
 *       整次不写入（先全部校验通过，再执行删插）；</li>
 *   <li><b>软删工具视为存在</b>（授权仍有效，恢复后可用）；<b>已彻底删除</b>（tb_tool 无记录）视为不存在 → {@code 1004}；</li>
 *   <li>列表查询含软删工具，按 id 升序；若底层已级联清理，orphan 自然被过滤。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ToolGrantService {

    private final ToolGrantMapper grantMapper;
    private final ToolMapper toolMapper;
    private final UserMapper userMapper;
    private final DeptMapper deptMapper;
    /** M-22：管理员识别 —— 复用用户域的单一判定，**不另写一份**"是否管理员" */
    private final UserService userService;
    /** v1.17：定向授权的集合变化判定（只算 diff，不落库） */
    private final ToolGrantChangeDetector changeDetector;

    // ---------------- 用户侧（§6.1）----------------

    /** 查询某用户被授权的工具（含软删，按 id 升序；不做可见范围校验） */
    public List<ToolBriefVO> listToolsForUser(Long userId) {
        UserDO user = userMapper.selectById(userId);
        Long userDeptId = user == null ? null : user.getDeptId();
        List<Long> toolIds = grantMapper.selectToolIdsByUser(userId);
        if (toolIds.isEmpty()) {
            return List.of();
        }
        Map<Long, ToolDO> map = toolMapper.selectBatchIds(toolIds).stream()
                .collect(Collectors.toMap(ToolDO::getId, t -> t));
        List<ToolBriefVO> result = new ArrayList<>(toolIds.size());
        for (Long tid : toolIds) {
            ToolDO t = map.get(tid);
            if (t == null) {
                continue; // 已彻底删除工具：级联清理后理论上不会出现，防御性跳过
            }
            // v1.14：过滤无意义配对——PUBLIC（全员本就可见）或工具部门 == 目标用户部门（本部门本就可见）
            if (Scope.PUBLIC.equals(t.getScope())) {
                continue;
            }
            if (userDeptId != null && userDeptId.equals(t.getDeptId())) {
                continue;
            }
            result.add(toToolBrief(t));
        }
        return result;
    }

    /**
     * 保存某用户的工具授权：全量覆盖（先删后插、单事务）；空数组 = 清空；自动去重；
     * 含无效 ID → {@code 1004} 不部分成功。
     *
     * <p><b>v1.17</b>：返回「可见工具集合**实际发生变化**」的用户 —— 变化则含该用户，否则空集合。
     * 调用方（Controller）据此在**事务提交后**推送 {@code GRANT_CHANGED}。
     * ⚠️ 判定基于**落库结果**（非入参），且只比"有意义配对"（PUBLIC / 同部门会被 v1.14 静默丢弃）。
     */
    @Transactional
    public List<Long> saveToolsForUser(Long userId, List<String> toolIds, Long grantBy) {
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(Code.NOT_FOUND, "用户不存在");
        }
        // M-22（契约 v1.13）：管理员对任意工具本就不受可见范围限制，定向授权对其**无语义** → 拒绝
        if (userService.isSystemAdmin(userId)) {
            throw new BusinessException(Code.CONFLICT, "管理员账号无需工具授权");
        }
        Long userDeptId = user.getDeptId();
        List<Long> distinctToolIds = parseAndDedup(toolIds, "工具ID");
        List<Long> toGrant = new ArrayList<>(distinctToolIds.size());
        for (Long tid : distinctToolIds) {
            ToolDO t = toolMapper.selectById(tid);
            if (t == null) {
                throw new BusinessException(Code.NOT_FOUND, "无效的工具 ID：" + IdUtils.toStr(tid));
            }
            // v1.14：静默丢弃无意义配对——PUBLIC（全员本就可见）或工具部门 == 目标用户部门（本部门本就可见）；
            // 不影响其余有效项，也不改变"不部分成功"语义（不存在的 ID 仍在上一步抛 1004）
            if (Scope.PUBLIC.equals(t.getScope())) {
                continue;
            }
            if (userDeptId != null && userDeptId.equals(t.getDeptId())) {
                continue;
            }
            toGrant.add(tid);
        }

        // v1.17：先取"落库前"的授权（用于判断集合是否**实际变化**），再写入
        List<Long> before = grantMapper.selectToolIdsByUser(userId);

        grantMapper.delete(Wrappers.<ToolGrantDO>lambdaQuery().eq(ToolGrantDO::getUserId, userId));
        if (!toGrant.isEmpty()) {
            batchInsert(userId, toGrant, grantBy);
        }

        return changeDetector.userGrantsChanged(before, userDeptId, toGrant) ? List.of(userId) : List.of();
    }

    // ---------------- 工具侧（§9.8，共用同一张表）----------------

    /** 查询某工具已授权的用户（按 id 升序；不做可见范围校验） */
    public List<UserBriefVO> listUsersForTool(Long toolId) {
        ToolDO tool = toolMapper.selectById(toolId);
        Long toolDeptId = tool == null ? null : tool.getDeptId();
        List<Long> userIds = grantMapper.selectUserIdsByTool(toolId);
        if (userIds.isEmpty()) {
            return List.of();
        }
        Map<Long, UserDO> map = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UserDO::getId, u -> u));
        List<UserBriefVO> result = new ArrayList<>(userIds.size());
        for (Long uid : userIds) {
            UserDO u = map.get(uid);
            if (u == null) {
                continue;
            }
            // v1.14：过滤无意义配对——工具所属部门人员（本部门本就可见）不展示在已授权列表
            if (toolDeptId != null && toolDeptId.equals(u.getDeptId())) {
                continue;
            }
            result.add(toUserBrief(u));
        }
        return result;
    }

    /**
     * 保存某工具的授权用户：语义与 {@link #saveToolsForUser} 完全一致（共用同一张表）。
     *
     * <p><b>v1.17</b>：返回「可见工具集合**实际发生变化**」的用户集合 ——
     * **新增者 ∪ 被移除者**（对称差）；"本就在且仍在"的人不算受影响（不会收到通知）。
     */
    @Transactional
    public List<Long> saveUsersForTool(Long toolId, List<String> userIds, Long grantBy) {
        ToolDO tool = toolMapper.selectById(toolId);
        if (tool == null) {
            throw new BusinessException(Code.NOT_FOUND, "工具不存在");
        }
        Long toolDeptId = tool.getDeptId();
        List<Long> distinctUserIds = parseAndDedup(userIds, "用户ID");
        List<Long> toGrant = new ArrayList<>(distinctUserIds.size());
        for (Long uid : distinctUserIds) {
            UserDO u = userMapper.selectById(uid);
            if (u == null) {
                throw new BusinessException(Code.NOT_FOUND, "无效的用户 ID：" + IdUtils.toStr(uid));
            }
            // M-22（契约 v1.13）：入参含管理员 → **整体拒绝**（不做静默过滤，避免调用方误以为已生效）
            if (userService.isSystemAdmin(uid)) {
                throw new BusinessException(Code.CONFLICT, "管理员账号无需工具授权");
            }
            // v1.14：静默丢弃无意义配对——工具所属部门人员（本部门本就可见）；
            // 不影响其余有效项，也不改变"不部分成功"语义（不存在的 ID 仍在上一步抛 1004）
            if (u.getDeptId() != null && u.getDeptId().equals(toolDeptId)) {
                continue;
            }
            toGrant.add(uid);
        }

        // v1.17：先取"落库前"的授权用户，再写入；随后按"有意义配对"算受影响者（**新增 ∪ 被移除**）
        List<Long> before = grantMapper.selectUserIdsByTool(toolId);

        grantMapper.delete(Wrappers.<ToolGrantDO>lambdaQuery().eq(ToolGrantDO::getToolId, toolId));
        if (!toGrant.isEmpty()) {
            batchInsertForTool(toolId, toGrant, grantBy);
        }

        return changeDetector.affectedUsers(before, toolDeptId, toGrant);
    }

    // ---------------- 内部 ----------------

    /** 解析 + 去重（保持首次出现顺序）；字段缺失 → {@code 1003}；格式非法 → IdUtils 抛 {@code 1003} */
    private List<Long> parseAndDedup(List<String> ids, String label) {
        if (ids == null) {
            throw new BusinessException(Code.PARAM_INVALID, label + " 不能为空");
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (String s : ids) {
            seen.add(IdUtils.parse(s, label));
        }
        return new ArrayList<>(seen);
    }

    private void batchInsert(Long userId, List<Long> toolIds, Long grantBy) {
        LocalDateTime now = LocalDateTime.now();
        List<ToolGrantDO> list = new ArrayList<>(toolIds.size());
        for (Long tid : toolIds) {
            list.add(of(tid, userId, grantBy, now));
        }
        grantMapper.batchInsert(list);
    }

    private void batchInsertForTool(Long toolId, List<Long> userIds, Long grantBy) {
        LocalDateTime now = LocalDateTime.now();
        List<ToolGrantDO> list = new ArrayList<>(userIds.size());
        for (Long uid : userIds) {
            list.add(of(toolId, uid, grantBy, now));
        }
        grantMapper.batchInsert(list);
    }

    private ToolGrantDO of(Long toolId, Long userId, Long grantBy, LocalDateTime now) {
        ToolGrantDO g = new ToolGrantDO();
        g.setToolId(toolId);
        g.setUserId(userId);
        g.setGrantBy(grantBy);
        g.setCreateTime(now);
        return g;
    }

    private ToolBriefVO toToolBrief(ToolDO t) {
        return ToolBriefVO.builder()
                .id(IdUtils.toStr(t.getId()))
                .name(t.getName())
                .deptName(deptName(t.getDeptId()))
                .latestVersion(t.getLatestVersion())
                .scope(t.getScope())
                .build();
    }

    private UserBriefVO toUserBrief(UserDO u) {
        return UserBriefVO.builder()
                .id(IdUtils.toStr(u.getId()))
                .username(u.getUsername())
                .realName(u.getRealName())
                .deptName(deptName(u.getDeptId()))
                .build();
    }

    private String deptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        DeptDO d = deptMapper.selectById(deptId);
        return d == null ? null : d.getName();
    }
}
