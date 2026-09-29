package com.example.toolbox.upload;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.security.JwtProvider;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.upload.service.ChunkStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片上传的**真实 HTTP** 通路（★ 2026-09-20 补：这是一个曾漏掉的真缺陷的回归防线）。
 *
 * <p><b>为什么必须单独写这一类</b>：`MockMvc` 与 service 级用例都**绕过 Tomcat 的 multipart 解析**，
 * 因此项目里"有没有配 `spring.servlet.multipart.*`"这件事**任何既有用例都验不到**。
 * 2026-09-20 联调时暴露：项目此前**完全没有 multipart 配置** → 用 Spring Boot 默认
 * `max-file-size = 1MB`，而前端分片是 **5MB** → 分片被拒；又因 Tomcat `max-swallow-size`
 * 默认仅 2MB，5MB 的 body 吞不下 → **直接断连**，前端只看到"无法连接服务器"，无从定位。
 *
 * <p>本类用 {@code RANDOM_PORT} 起真实容器 + 真 5MB 分片，断言：
 * ① 请求被正常接收（不是 500/断连）；② 返回业务成功码 `code = 0`。
 * 若 multipart 上限被调回默认值，本类会**立即失败**（已用缺陷注入验证）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadHttpMultipartTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private ChunkStorage chunkStorage;

    private static final String IDENT = "http-multipart-probe";

    /** 真 5MB 分片（与前端一致）经真实 HTTP 上传 → 必须被接收并落到临时分片目录 */
    @Test
    void fiveMegabyteChunkAcceptedOverRealHttp() {
        byte[] chunk = new byte[5 * 1024 * 1024];   // 5 MB，超过 Spring Boot 默认 max-file-size=1MB

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("chunk", new ByteArrayResource(chunk) {
            @Override
            public String getFilename() {
                return "a.bin";
            }
        });
        body.add("identifier", IDENT);
        body.add("chunkNumber", "1");
        body.add("totalChunks", "1");
        body.add("fileName", "a.bin");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(signToken(LoginUser.builder()
                .userId(999_999L).username("uploader").deptId(1L)
                .permissions(Set.of(PermissionCode.TOOL_UPLOAD)).build()));

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/upload/chunk", new HttpEntity<>(body, headers), String.class);

        String respBody = resp.getBody() == null ? "" : resp.getBody();
        assertEquals(200, resp.getStatusCode().value(),
                "真实 HTTP 下 5MB 分片应被接收（非 500 / 非断连）。实际响应：" + respBody);
        assertTrue(respBody.contains("\"code\":0"),
                "应返回业务成功码 code=0（若为 1000/1003，说明 multipart 上限或 swallow 配置有问题）。实际：" + respBody);

        // 分片确实落到了临时目录（证明是真落盘，而非仅通过校验）
        assertEquals(1, chunkStorage.uploadedChunks(IDENT).size(), "分片应已落盘");
        chunkStorage.clean(IDENT);   // 自清理，不留中间文件
    }
}
