-- =============================================================================
-- 09-grant-tool-assign.sql   （M-25 / 契约 v1.23）
-- 作用：给**既有**全局管理员（持 sys:setting 者）补发第 9 个权限点 tool:assign
-- -----------------------------------------------------------------------------
-- 为什么需要这个脚本（不是可选项）：
--   `SystemService.init` 只在**首次初始化**（库中无用户）时按 `PermissionDict`
--   一次性授予"当时的全部权限点"。**已经在跑的库不会自动补发新增权限点** ——
--   于是升级到 v1.23 后，管理员虽然仍是全局管理员，但令牌里没有 tool:assign，
--   点「调整归属部门」会直接 403（前端提示"归属部门调整失败：无操作权限"）。
--
-- 幂等：NOT EXISTS 判重，可重复执行。
--
-- ⚠️ 执行后必须**重新登录**：
--   权限以 `perms` 声明写在 JWT 里（JwtProvider#sign），旧令牌仍是旧的 8 个权限点，
--   不退出重登就不会生效。
-- =============================================================================

INSERT INTO tb_user_permission (user_id, permission_code, create_time)
SELECT g.user_id, 'tool:assign', NOW()
FROM (
    SELECT DISTINCT user_id
    FROM tb_user_permission
    WHERE permission_code = 'sys:setting'
) g
WHERE NOT EXISTS (
    SELECT 1
    FROM tb_user_permission p
    WHERE p.user_id = g.user_id
      AND p.permission_code = 'tool:assign'
);

-- 校验（期望：每个全局管理员各 1 行）
-- SELECT u.id, u.username, p.permission_code
--   FROM tb_user u
--   JOIN tb_user_permission p ON p.user_id = u.id
--  WHERE p.permission_code = 'tool:assign';
