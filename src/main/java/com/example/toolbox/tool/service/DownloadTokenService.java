package com.example.toolbox.tool.service;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.SessionRegistry;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.vo.DownloadTokenVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 短时效下载令牌签发（契约 §9.7 / N-13）。
 *
 * <p>校验口径与下载接口**完全一致**（契约 §9.7）：
 * 登录 → 401；无 tool:download → 403（由注解拦截器完成）；
 * 不在可见范围 → 403；工具已删除 → 2004。
 *
 * <p>M-18：令牌额外绑定**当前会话**（{@code sid}）—— 会话被替代 / 登出 / 停用后，
 * 该令牌即便自身未过期也不可再用（方案 §6 第 7 条）。
 */
@Service
@RequiredArgsConstructor
public class DownloadTokenService {

    private final ToolService toolService;
    private final DownloadTokenStore tokenStore;
    private final SessionRegistry sessionRegistry;

    /** 换取令牌：校验通过后签发绑定「用户 + 工具 + 版本 + 会话」的短时效令牌 */
    public DownloadTokenVO issue(Long toolId, String versionId) {
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        // 与下载接口同口径：不存在 → 1004；已删除 → 2004；不可见 → 403（含 M-17 定向授权例外）
        ToolDO tool = toolService.requireVisibleForRead(toolId, user);

        Long resolvedVersionId = resolveVersionId(tool, versionId);

        String token = tokenStore.issue(user.getUserId(), toolId, resolvedVersionId,
                requireCurrentSid(user.getUserId()));
        return DownloadTokenVO.builder()
                .token(token)
                .expiresIn(DownloadTokenStore.TTL_SECONDS)
                .build();
    }

    private Long resolveVersionId(ToolDO tool, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            if (tool.getLatestVersionId() == null || tool.getLatestVersionId() <= 0) {
                throw new BusinessException(Code.NOT_FOUND, "工具暂无可用版本");
            }
            return tool.getLatestVersionId();
        }
        Long vid;
        try {
            vid = Long.valueOf(versionId.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(Code.PARAM_INVALID, "版本 ID 格式非法");
        }
        ToolVersionDO v = toolService.findVersion(vid);
        // 版本不存在或不属于该工具 → 1004（防跨工具取文件）
        if (v == null || !v.getToolId().equals(tool.getId())) {
            throw new BusinessException(Code.NOT_FOUND, "版本不存在");
        }
        return vid;
    }

    /**
     * M-18：令牌必须绑定"当前会话"。正常路径下 Bearer 请求已过 JwtAuthFilter，会话必定存在；
     * 此处作为单一入口兜底，取不到会话一律视为未登录（401）。
     */
    private String requireCurrentSid(Long userId) {
        String sid = sessionRegistry.currentSid(userId);
        if (sid == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        return sid;
    }
}
