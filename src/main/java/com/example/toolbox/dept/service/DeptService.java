package com.example.toolbox.dept.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.toolbox.common.constant.StatusValue;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.log.AuditLogger;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.util.DateTimeUtils;
import com.example.toolbox.common.util.IdUtils;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.dto.DeptStatusDTO;
import com.example.toolbox.dept.entity.DeptDO;
import com.example.toolbox.dept.mapper.DeptMapper;
import com.example.toolbox.dept.vo.DeptVO;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.user.entity.UserDO;
import com.example.toolbox.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 部门服务（契约 §四）：列表（全量、可按状态筛选）/ 新建 / 编辑 / 启用停用。
 * 约束：名称不可重复 → 1005；部门下仍有用户时不允许停用 → 1005（message 含人数）。
 * 部门为扁平结构，本期不使用 parent_id。
 */
@Service
@RequiredArgsConstructor
public class DeptService {

    private final DeptMapper deptMapper;
    private final UserMapper userMapper;
    private final AuditLogger auditLogger;

    public List<DeptVO> list(Integer status) {
        var w = Wrappers.<DeptDO>lambdaQuery();
        if (status != null) {
            w.eq(DeptDO::getStatus, status);
        }
        w.orderByAsc(DeptDO::getId);
        return deptMapper.selectList(w).stream().map(this::toVO).toList();
    }

    @Transactional
    public String create(DeptSaveDTO dto) {
        if (nameExists(dto.getName())) {
            throw new BusinessException(Code.CONFLICT, "部门名称已存在");
        }
        DeptDO d = new DeptDO();
        d.setName(dto.getName());
        d.setRemark(dto.getRemark());
        d.setStatus(StatusValue.ENABLED);
        d.setCreateTime(LocalDateTime.now());
        deptMapper.insert(d);
        auditLogger.write(currentOperator(), "DEPT_CREATE", "dept:" + d.getId(), "OK");
        return IdUtils.toStr(d.getId());
    }

    @Transactional
    public void update(Long id, DeptSaveDTO dto) {
        DeptDO d = requireDept(id);
        boolean renamed = !d.getName().equals(dto.getName());
        if (renamed && nameExists(dto.getName())) {
            throw new BusinessException(Code.CONFLICT, "部门名称已存在");
        }
        d.setName(dto.getName());
        d.setRemark(dto.getRemark());
        d.setUpdateTime(LocalDateTime.now());
        deptMapper.updateById(d);
        auditLogger.write(currentOperator(), "DEPT_UPDATE", "dept:" + id, "OK");
    }

    @Transactional
    public void updateStatus(Long id, DeptStatusDTO dto) {
        DeptDO d = requireDept(id);
        if (!StatusValue.isValid(dto.getStatus())) {
            throw new BusinessException(Code.PARAM_INVALID, "status 仅支持 0（停用）或 1（启用）");
        }
        long userCount = countUsers(id);
        if (dto.getStatus() == StatusValue.DISABLED && userCount > 0) {
            throw new BusinessException(Code.CONFLICT,
                    "部门下仍有 " + userCount + " 个用户，不可停用；请先将用户调整至其他部门");
        }
        // 幂等：目标状态与当前一致时视为成功
        if (d.getStatus() != null && d.getStatus().equals(dto.getStatus())) {
            return;
        }
        d.setStatus(dto.getStatus());
        d.setUpdateTime(LocalDateTime.now());
        deptMapper.updateById(d);
        auditLogger.write(currentOperator(), "DEPT_STATUS", "dept:" + id + ":" + dto.getStatus(), "OK");
    }

    /** 当前操作人账号（审计日志用）；上下文缺失时由 AuditLogger 降级处理 */
    private String currentOperator() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getUsername();
    }

    /** 部门不存在 → 1004 */
    private DeptDO requireDept(Long id) {
        DeptDO d = deptMapper.selectById(id);
        if (d == null) {
            throw new BusinessException(Code.NOT_FOUND, "部门不存在");
        }
        return d;
    }

    private boolean nameExists(String name) {
        return deptMapper.selectCount(
                Wrappers.<DeptDO>lambdaQuery().eq(DeptDO::getName, name)) > 0;
    }

    /** 部门下用户数（供列表展示与停用校验复用） */
    public long countUsers(Long deptId) {
        return userMapper.selectCount(
                Wrappers.<UserDO>lambdaQuery().eq(UserDO::getDeptId, deptId));
    }

    private DeptVO toVO(DeptDO d) {
        return DeptVO.builder()
                .id(IdUtils.toStr(d.getId()))
                .name(d.getName())
                .remark(d.getRemark())
                .status(d.getStatus())
                .userCount(countUsers(d.getId()))
                .createTime(DateTimeUtils.format(d.getCreateTime()))
                .build();
    }
}
