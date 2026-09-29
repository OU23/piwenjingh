package com.example.toolbox.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.PermissionDict;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.result.PageResult;
import com.example.toolbox.common.util.DateTimeUtils;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.SessionService;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.user.dto.UserSaveDTO;
import com.example.toolbox.user.dto.UserStatusDTO;
import com.example.toolbox.user.dto.UserUpdateDTO;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.entity.UserPermissionDO;
import com.example.toolbox.user.mapper.UserMapper;
import com.example.toolbox.user.mapper.UserPermissionMapper;
import com.example.toolbox.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户服务（契约 §六）：列表 / 详情 / 新建 / 编辑 / 启用停用 / 权限查询与全量覆盖保存。
 * 约束：账号重复 → 1005；部门不存在 → 1004；权限编码非法 → 1003；
 *       不得停用当前登录账号自身 → 1005。
 * 归属部门一律取提交值（管理员代建），但不接受任何前端传入的用户身份字段。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    /** 分页默认与上限（与 MybatisPlusConfig 的 maxLimit 保持一致） */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final UserMapper userMapper;
    private final UserPermissionMapper userPermissionMapper;
    private final DeptMapper deptMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogger auditLogger;
    /** 会话终结（M-18）：停用账号时立即踢下线 */
    private final SessionService sessionService;
    /** 系统管理员身份判定（M-22 v1.13；从本类抽出，与 `sys:setting` 权限点解耦） */
    private final AdminIdentityService adminIdentityService;

    /** 分页列表（关键词：账号 / 姓名；可按部门筛选） */
    public PageResult<UserVO> page(int pageNum, int pageSize, String keyword, String deptId) {
        int pn = pageNum <= 0 ? 1 : pageNum;
        int ps = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        Page<UserDO> page = userMapper.selectPage(new Page<>(pn, ps), buildQuery(keyword, deptId));
        List<UserVO> list = page.getRecords().stream().map(u -> toVO(u, false)).toList();
        return PageResult.of(list, page.getTotal(), pn, ps);
    }

    /**
     * 导出用全量列表（不分页）。
     * **与 {@link #page} 同筛选、同排序**（共用 {@link #buildQuery}），避免"导出与列表结果不一致"。
     */
    public List<UserVO> exportList(String keyword, String deptId) {
        return userMapper.selectList(buildQuery(keyword, deptId)).stream()
                .map(u -> toVO(u, false))
                .toList();
    }

    /** 列表与导出共用的查询条件（单一入口，口径不会漂移） */
    private LambdaQueryWrapper<UserDO> buildQuery(String keyword, String deptId) {
        var w = Wrappers.<UserDO>lambdaQuery();
        // M-22 v1.13 修正：列表/导出只排除「系统初始化管理员」（id 最小且持 sys:setting 者），
        // 与 sys:setting 权限点解耦 —— 其他即便被赋全权的普通账号（test1）仍可见可管理。唯一收口。
        Long adminId = adminIdentityService.getSystemAdminId();
        if (adminId != null) {
            w.ne(UserDO::getId, adminId);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(UserDO::getUsername, keyword).or().like(UserDO::getRealName, keyword));
        }
        if (deptId != null && !deptId.isBlank()) {
            w.eq(UserDO::getDeptId, IdUtils.parse(deptId, "部门ID"));
        }
        w.orderByDesc(UserDO::getCreateTime);
        return w;
    }

    /** 详情（含权限点） */
    public UserVO detail(Long id) {
        return toVO(requireUser(id), true);
    }

    @Transactional
    public String create(UserSaveDTO dto) {
        if (usernameExists(dto.getUsername())) {
            throw new BusinessException(Code.CONFLICT, "账号已存在");
        }
        Long deptId = IdUtils.parse(dto.getDeptId(), "部门ID");
        requireDept(deptId);

        UserDO u = new UserDO();
        u.setUsername(dto.getUsername());
        u.setPassword(passwordEncoder.encode(dto.getPassword()));
        u.setRealName(dto.getRealName());
        u.setDeptId(deptId);
        u.setStatus(StatusValue.ENABLED);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);

        // v1.14：新建账号默认仅 `tool:download`（见 resolveCreatePermissions）；导入复用本方法且从不设 permissions。
        savePermissions(u.getId(), resolveCreatePermissions(dto));
        auditLogger.write(currentOperator(), "USER_CREATE", "user:" + u.getId(), "OK");
        return IdUtils.toStr(u.getId());
    }

    /**
     * v1.14：解析新建账号的初始权限。
     * <ul>
     *   <li>{@code permissions == null}（未传）→ 默认仅 {@link PermissionCode#TOOL_DOWNLOAD}（不含上传等）；</li>
     *   <li>显式传 {@code []} → 仍表示"无权限"（尊重管理员在编辑界面主动清空的决定）；</li>
     * </ul>
     * 仅 {@code null} 触发默认；导入账号复用本方法且从不设 permissions → 自动获得仅下载权限。
     */
    /**
     * v1.14：解析新建账号的初始权限。
     * <ul>
     *   <li>{@code permissions == null}（未传）→ 默认仅 {@link PermissionCode#TOOL_DOWNLOAD}（不含上传等）；</li>
     *   <li>显式传 {@code []} → 仍表示"无权限"（尊重管理员在编辑界面主动清空的决定）；</li>
     * </ul>
     * 仅 {@code null} 触发默认；导入账号复用本方法且从不设 permissions → 自动获得仅下载权限。
     */
    private List<String> resolveCreatePermissions(UserSaveDTO dto) {
        return dto.getPermissions() == null
                ? List.of(PermissionCode.TOOL_DOWNLOAD)
                : dto.getPermissions();
    }

    /** 编辑：仅姓名与归属部门可改，账号不可改 */
    @Transactional
    public void update(Long id, UserUpdateDTO dto) {
        UserDO u = requireUser(id);
        Long deptId = IdUtils.parse(dto.getDeptId(), "部门ID");
        requireDept(deptId);
        u.setRealName(dto.getRealName());
        u.setDeptId(deptId);
        u.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(u);
        auditLogger.write(currentOperator(), "USER_UPDATE", "user:" + id, "OK");
    }

    /** 启用 / 停用（契约 §六，v1.4）。停用立即生效（登录以 status 判定）。 */
    @Transactional
    public void updateStatus(Long id, UserStatusDTO dto) {
        UserDO u = requireUser(id);
        if (!StatusValue.isValid(dto.getStatus())) {
            throw new BusinessException(Code.PARAM_INVALID, "status 仅支持 0（停用）或 1（启用）");
        }
        // 防止管理员把自己锁出系统
        LoginUser current = UserContext.get();
        if (dto.getStatus() == StatusValue.DISABLED && current != null && id.equals(current.getUserId())) {
            throw new BusinessException(Code.CONFLICT, "不能停用当前登录账号自身");
        }
        // M-22（契约 v1.13）：不得停用管理员账号（**启用不受限**）——
        // 防止持 user:manage 的普通账号把管理员锁在门外（登录以 status 判定 → 停用即 1006）
        if (dto.getStatus() == StatusValue.DISABLED && isSystemAdmin(id)) {
            throw new BusinessException(Code.CONFLICT, "不能停用管理员账号");
        }
        // 幂等：目标状态与当前一致时视为成功
        if (u.getStatus() != null && u.getStatus().equals(dto.getStatus())) {
            return;
        }
        u.setStatus(dto.getStatus());
        u.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(u);
        // M-18：停用立即生效 —— 移除该账号会话并关闭其 WebSocket 连接（启用无需处理）
        if (dto.getStatus() == StatusValue.DISABLED) {
            sessionService.forceLogout(id);
        }
        auditLogger.write(currentOperator(), "USER_STATUS", "user:" + id + ":" + dto.getStatus(), "OK");
    }

    /** 查询用户已分配权限点 */
    public List<String> getPermissions(Long userId) {
        requireUser(userId);
        return userPermissionMapper.selectList(
                        Wrappers.<UserPermissionDO>lambdaQuery().eq(UserPermissionDO::getUserId, userId))
                .stream().map(UserPermissionDO::getPermissionCode).toList();
    }

    /**
     * 全量覆盖保存权限点（先删后插，同一事务）。
     * 传空集合 = 清空；编码非法 → 1003。
     */
    @Transactional
    public void savePermissions(Long userId, List<String> codes) {
        // M-22 v1.13 修正：系统管理员（初始化首个账号）的权限点不可被任何账号修改（含自己）；
        // 与 sys:setting 解耦 —— 其他全权普通账号不受限（自愈通道：移除 sys:setting 后可由他人恢复）。
        if (isSystemAdmin(userId)) {
            throw new BusinessException(Code.CONFLICT, "管理员账号的权限点不可修改");
        }
        List<String> target = codes == null ? List.of() : codes;
        for (String c : target) {
            if (!PermissionDict.isValidCode(c)) {
                throw new BusinessException(Code.PARAM_INVALID, "非法权限点编码：" + c);
            }
        }
        userPermissionMapper.delete(
                Wrappers.<UserPermissionDO>lambdaQuery().eq(UserPermissionDO::getUserId, userId));
        if (target.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (String c : target) {
            UserPermissionDO p = new UserPermissionDO();
            p.setUserId(userId);
            p.setPermissionCode(c);
            p.setCreateTime(now);
            userPermissionMapper.insert(p);
        }
        auditLogger.write(currentOperator(), "USER_GRANT", "user:" + userId, "OK");
    }

    /**
     * 更新权限点并返回**是否实际发生变化**（M-21 / 契约 §3.3 v1.12）。
     *
     * <p>⚠️ 刻意**不加 `@Transactional`**：让 {@link #savePermissions} 自身的事务先提交，
     * 回到调用方（Controller）后再做会话联动 —— 避免"库回滚了却把人在线踢了"。
     *
     * @return true = 权限集合发生了实际变化（需要触发下线通知）
     */
    public boolean updatePermissions(Long userId, List<String> codes) {
        Set<String> before = new HashSet<>(getPermissions(userId));
        savePermissions(userId, codes);
        Set<String> after = new HashSet<>(getPermissions(userId));
        return !before.equals(after);
    }

    /**
     * 该账号是否为全局管理员（持 `sys:setting`）—— M-21 豁免判断用。
     * 判定口径与契约 §6.1 / §9.4（v1.12）一致：**持 `sys:setting` 即视为全局管理员**。
     */
    public boolean isGlobalAdmin(Long userId) {
        return getPermissions(userId).contains(PermissionCode.SYS_SETTING);
    }

    /**
     * 某账号是否为系统管理员（初始化创建的首个账号；与 `sys:setting` 权限点解耦）。
     * 委托 {@link AdminIdentityService}，详见其类注释。
     */
    public boolean isSystemAdmin(Long userId) {
        return adminIdentityService.isSystemAdmin(userId);
    }

    /** 按账号查用户（供认证登录使用）；不存在返回 null */
    public UserDO findByUsername(String username) {
        return userMapper.selectOne(
                Wrappers.<UserDO>lambdaQuery().eq(UserDO::getUsername, username));
    }

    /** 用户是否存在（供初始化幂等判断使用） */
    public boolean anyUserExists() {
        return userMapper.selectCount(null) > 0;
    }

    /** 加载权限集合（供认证构建登录上下文） */
    public Set<String> loadPermissionSet(Long userId) {
        return new HashSet<>(userPermissionMapper.selectList(
                        Wrappers.<UserPermissionDO>lambdaQuery().eq(UserPermissionDO::getUserId, userId))
                .stream().map(UserPermissionDO::getPermissionCode).toList());
    }

    public String deptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        DeptDO d = deptMapper.selectById(deptId);
        return d == null ? null : d.getName();
    }

    // ---------------- 内部 ----------------

    /**
     * 当前操作人账号（审计日志用）。登录上下文中不存在时返回 null，
     * 由 AuditLogger 自行降级为 "anonymous"，不阻断业务。
     */
    private String currentOperator() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getUsername();
    }

    private UserDO requireUser(Long id) {
        UserDO u = userMapper.selectById(id);
        if (u == null) {
            throw new BusinessException(Code.NOT_FOUND, "用户不存在");
        }
        return u;
    }

    /**
     * 账号是否存在（v1.16 批量操作：用于**忽略不存在 / 已删除 ID**，不抛异常）。
     * 用户表无软删除，"已删除"即"不存在"。
     */
    public boolean exists(Long id) {
        return id != null && userMapper.selectById(id) != null;
    }

    private void requireDept(Long deptId) {
        if (deptMapper.selectById(deptId) == null) {
            throw new BusinessException(Code.NOT_FOUND, "归属部门不存在");
        }
    }

    private boolean usernameExists(String username) {
        return userMapper.selectCount(
                Wrappers.<UserDO>lambdaQuery().eq(UserDO::getUsername, username)) > 0;
    }

    UserVO toVO(UserDO u, boolean withPermissions) {
        return UserVO.builder()
                .id(IdUtils.toStr(u.getId()))
                .username(u.getUsername())
                .realName(u.getRealName())
                .deptId(IdUtils.toStr(u.getDeptId()))
                .deptName(deptName(u.getDeptId()))
                .status(u.getStatus())
                .permissions(withPermissions ? getPermissions(u.getId()) : null)
                .createTime(DateTimeUtils.format(u.getCreateTime()))
                .build();
    }
}
