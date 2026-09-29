package com.example.toolbox.tool.controller;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.common.result.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.security.PermissionChecker;
import com.example.toolbox.security.UserContext;
import com.example.toolbox.tool.service.DownloadService;
import com.example.toolbox.tool.service.DownloadTokenStore;
import com.example.toolbox.tool.service.ToolService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 下载（契约 §9.5 + §9.7）。★ 本阶段最高风险点。
 *
 * <p><b>为什么不用统一响应体</b>：契约 §9.5 明确「成功时 HTTP 200，
 * Content-Type: application/octet-stream，直接返回文件流，**不使用统一响应体**」。
 * 失败时仍返回 JSON 统一响应体（由 GlobalExceptionHandler 处理）。
 *
 * <p><b>鉴权双通道</b>（契约 §9.5 v1.3 / N-13）：
 * <ol>
 *   <li>{@code Authorization: Bearer} —— 常规 fetch / Axios 请求；</li>
 *   <li>{@code ?token=} —— 原生 {@code <a>} 导航请求（非安全上下文），
 *       令牌由 {@code POST /api/tools/{id}/download-token} 换取，
 *       **绑定「用户 + 工具 + 版本」、短时效、一次性**。</li>
 * </ol>
 *
 * <p><b>为何本控制器不受 {@code @RequiresPermission} 保护</b>：
 * 下载既要支持 Bearer 通道，也要支持 {@code ?token=} 通道，
 * 而拦截器只认 {@link UserContext}（由 Bearer 过滤器写入）。
 * 因此权限校验**在本控制器内显式完成**，两条通道各自校验后放行 ——
 * 校验逻辑本身仍走 {@code ToolService.requireVisibleTool}（R3 唯一入口），未自行重写判定。
 */
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadService downloadService;
    private final DownloadTokenStore tokenStore;
    private final ToolService toolService;
    private final PermissionChecker permissionChecker;
    private final ObjectMapper objectMapper;

    /** 下载工具文件（GET：返回文件流，支持 Range → 206） */
    @RequestMapping(value = "/{id:\\d+}/download", method = RequestMethod.GET)
    public void download(@org.springframework.web.bind.annotation.PathVariable Long id,
                         @RequestParam(required = false) String versionId,
                         @RequestParam(required = false) String token,
                         @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                         HttpServletRequest request,
                         HttpServletResponse response) throws java.io.IOException {

        try {
            LoginUser user = resolveUser(token, id, versionId);

            // counting=true：参与 N-02 计数判断（起点 0 或无 Range 才计数）
            DownloadService.DownloadPlan plan =
                    downloadService.planWithRange(id, versionId, rangeHeader, user, true);

            boolean partial = plan.ranged();
            response.setStatus(partial ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
            response.setContentType("application/octet-stream");
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(plan.contentLength()));
            if (partial) {
                response.setHeader(HttpHeaders.CONTENT_RANGE,
                        "bytes " + plan.start() + "-" + plan.end() + "/" + plan.size());
            }
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    buildContentDisposition(plan.version().getFileName()));

            // ★ 先计数再写流：约定"成功下载"计数；若写出过程失败，计数已发生。
            //   权衡：契约 §9.5 要求"失败响应不计数"，此处失败指**鉴权/校验失败**（已在上面抛异常拦掉）；
            //   传输中途断开属不可控，按"已开始下载"计 1 次，与前端预期一致。
            if (plan.countable()) {
                downloadService.increaseCount(plan);
            }

            // 流式写出（R4：不把文件读入内存）
            try (InputStream in = downloadService.openStream(plan);
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
        } catch (BusinessException e) {
            // B5 T2：下载链路「资源已不存在」（2004 / 2005）统一回 404，响应体仍为统一响应体
            if (e.getCode() == Code.TOOL_DELETED || e.getCode() == Code.FILE_KEY_INVALID) {
                writeResourceGone(response, e.getCode(), e.getMessage());
                return;
            }
            throw e;
        }
    }

    /** 探测文件大小（HEAD：不返回响应体，**一律不计数**） */
    @RequestMapping(value = "/{id:\\d+}/download", method = RequestMethod.HEAD)
    public void head(@org.springframework.web.bind.annotation.PathVariable Long id,
                     @RequestParam(required = false) String versionId,
                     @RequestParam(required = false) String token,
                     HttpServletResponse response) {
        try {
            LoginUser user = resolveUser(token, id, versionId);

            // counting=false：HEAD 一律不计数（契约 §9.5 + N-02）
            DownloadService.DownloadPlan plan = downloadService.plan(id, versionId, user, false);

            response.setStatus(HttpStatus.OK.value());
            response.setContentType("application/octet-stream");
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            // 必须返回**文件总大小**（契约 §9.5），不是本次响应体长度
            response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(plan.size()));
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    buildContentDisposition(plan.version().getFileName()));
        } catch (BusinessException e) {
            // B5 T2：HEAD 不写 body（RFC 9110）；「资源已不存在」仅置 404 状态码
            if (e.getCode() == Code.TOOL_DELETED || e.getCode() == Code.FILE_KEY_INVALID) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return;
            }
            throw e;
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 解析当前下载者：优先 {@code ?token=}（导航请求），否则用 Bearer 写入的登录上下文。
     * 两条通道都**必须**校验权限与可见范围；不满足一律 401/403
     * （契约 §9.5 约束：「无 `tool:download` 权限 → 403」两条通道同样适用）。
     */
    private LoginUser resolveUser(String token, Long toolId, String versionId) {
        if (token != null && !token.isBlank()) {
            // ?token= 通道：令牌绑定用户 + 工具 + 版本，需先定位版本 ID
            Long resolvedVersionId = resolveVersionIdForToken(toolId, versionId);
            DownloadTokenStore.Binding binding =
                    tokenStore.consume(token, toolId, resolvedVersionId);
            // 令牌已校验绑定关系；据此重建最小登录上下文用于权限与可见范围判定
            LoginUser fromToken = LoginUser.builder()
                    .userId(binding.userId())
                    .username(null)
                    .deptId(deptIdOf(binding.userId()))
                    .permissions(permissionsOf(binding.userId()))
                    .build();
            // 权限点与可见范围再各校验一次（令牌虽绑定，仍需确认此刻仍有权限）
            permissionChecker.require(fromToken, PermissionCode.TOOL_DOWNLOAD);
            downloadService.requireVisibleForDownload(toolId, fromToken);
            return fromToken;
        }

        // Bearer 通道：JwtAuthFilter 已写入上下文；权限点因本控制器未走注解拦截器，此处显式校验
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BusinessException(Code.UNAUTHENTICATED);
        }
        permissionChecker.require(user, PermissionCode.TOOL_DOWNLOAD);
        return user;
    }

    /** 令牌通道下，把 versionId 解析为具体版本 ID（不传则取最新版） */
    private Long resolveVersionIdForToken(Long toolId, String versionId) {
        com.example.toolbox.tool.entity.ToolDO tool = toolService.findById(toolId);
        if (tool == null) {
            throw new BusinessException(Code.TOOL_DELETED, "工具不存在");
        }
        if (versionId == null || versionId.isBlank()) {
            return tool.getLatestVersionId();
        }
        try {
            return Long.valueOf(versionId.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(Code.PARAM_INVALID, "版本 ID 格式非法");
        }
    }

    /**
     * 令牌通道的用户部门/权限：从库实时读取，保证"改了权限立刻生效"。
     * 由 ToolService 暴露的读取能力提供（避免在控制器里直接依赖 Mapper）。
     */
    private Long deptIdOf(Long userId) {
        return toolService.findUserDeptId(userId);
    }

    private java.util.Set<String> permissionsOf(Long userId) {
        return toolService.findUserPermissions(userId);
    }

    /**
     * Content-Disposition：ASCII 名 + UTF-8 编码名双写（契约 §9.5，支持中文）。
     * 形如：{@code attachment; filename="tool.zip"; filename*=UTF-8''%E5%B7%A5%E5%85%B7.zip}
     */
    private String buildContentDisposition(String fileName) {
        String name = (fileName == null || fileName.isBlank()) ? "download" : fileName;
        // ASCII 兜底名：非 ASCII 字符替换为下划线，避免 header 编码异常
        String ascii = name.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "'");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    /**
     * 下载链路「资源已不存在」（2004 / 2005）→ 404 + 统一响应体 JSON（B5 T2）。
     * 复用 {@link R#fail} 保证与全局异常处理器同构，避免硬编码 JSON 结构。
     */
    private void writeResourceGone(HttpServletResponse response, Code code, String message) throws java.io.IOException {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(code, message)));
    }
}
