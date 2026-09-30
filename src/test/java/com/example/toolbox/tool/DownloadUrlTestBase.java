package com.example.toolbox.tool;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.security.LoginUser;
import com.example.toolbox.storage.OssProperties;
import com.example.toolbox.storage.OssStorageService;
import com.example.toolbox.storage.StorageProperties;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.DownloadUrlService;
import com.example.toolbox.tool.service.ToolWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 下载地址签发相关测试的公共夹具（M-22 / B13）。
 *
 * <p>抽出来的原因：分流用例与失败用例共用同一套数据构造；**拆文件是为了守住"单文件 ≤300 行"的门禁**，
 * 不是为了减少断言。
 *
 * <p>OSS 实现以 {@link MockBean} 替换（不打真实 OSS），本地实现走真实磁盘（H2 + 临时目录）。
 */
abstract class DownloadUrlTestBase extends IntegrationTestBase {

    /** 桩返回的签名直链（绝不在测试里发真实请求） */
    protected static final String SIGNED_URL =
            "https://toolbox-files.oss-cn-hangzhou.aliyuncs.com/tools/abc.zip?Expires=1759212600&Signature=x";
    protected static final long ADMIN_ID = 999_999L;

    @Autowired
    protected DownloadUrlService downloadUrlService;
    @Autowired
    protected OssProperties ossProperties;
    @Autowired
    protected StorageProperties storageProperties;

    @Autowired
    private ToolWriteService toolWriteService;
    @Autowired
    private DeptService deptService;
    @Autowired
    private StorageService storageService;

    /** 替换 OSS 实现：只验证"分流是否走了 OSS 分支"，不触外网 */
    @MockBean
    protected OssStorageService ossStorageService;

    protected Long deptA;
    protected Long deptB;
    protected Long localToolId;
    protected Long localVersionId;
    protected Long ossToolId;
    protected Long ossVersionId;

    @BeforeEach
    void setUpData() {
        deptA = createDept("研发部");
        deptB = createDept("市场部");
        loginAs(ADMIN_ID, "admin", deptA, PermissionCode.TOOL_UPLOAD, PermissionCode.TOOL_DOWNLOAD);

        Map<String, String> local = createToolWithFile("本地工具", "local.bin");
        localToolId = Long.valueOf(local.get("toolId"));
        localVersionId = Long.valueOf(local.get("versionId"));

        Map<String, String> oss = createToolWithFile("OSS 工具", "remote.bin");
        ossToolId = Long.valueOf(oss.get("toolId"));
        ossVersionId = Long.valueOf(oss.get("versionId"));
        markBackend(ossVersionId, "oss");
    }

    // ---------------- 夹具 ----------------

    protected LoginUser currentUser() {
        return LoginUser.builder().userId(ADMIN_ID).username("admin").deptId(deptA)
                .permissions(Set.of(PermissionCode.TOOL_DOWNLOAD)).build();
    }

    protected Long createDept(String name) {
        loginAs(ADMIN_ID, "admin", null, PermissionCode.DEPT_MANAGE);
        DeptSaveDTO dto = new DeptSaveDTO();
        dto.setName(name);
        return Long.valueOf(deptService.create(dto));
    }

    /** 建一个工具（文件真实落盘） */
    protected Map<String, String> createToolWithFile(String name, String fileName) {
        String key = storageService.generateKey();
        byte[] content = new byte[64];
        try {
            storageService.save(key, new ByteArrayInputStream(content), content.length);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        ToolSaveDTO dto = new ToolSaveDTO();
        dto.setName(name);
        dto.setVersion("v1.0");
        dto.setIntro("简介");
        dto.setFileExt("bin");
        dto.setScope(Scope.PUBLIC);
        dto.setFileKey(key);
        dto.setFileName(fileName);
        dto.setFileSize((long) content.length);
        return toolWriteService.create(dto);
    }

    /** 删掉物理文件（模拟「DB 还记着本地落点、磁盘文件已丢」；不能靠业务接口造，创建时会校验文件存在） */
    protected void dropLocalFile(Long versionId) {
        ToolVersionDO version = toolVersionMapper.selectById(versionId);
        try {
            storageService.delete(version.getFileKey());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected void markBackend(Long versionId, String backend) {
        ToolVersionDO version = toolVersionMapper.selectById(versionId);
        version.setStorageBackend(backend);
        toolVersionMapper.updateById(version);
    }

    protected void makeDeptScoped(Long toolId) {
        ToolDO tool = toolMapper.selectById(toolId);
        tool.setScope(Scope.DEPT);
        toolMapper.updateById(tool);
    }

    /** 让 OSS 桩正常响应：存在 + 返回签名直链 */
    protected void stubOssSigned() {
        when(ossStorageService.exists(anyString())).thenReturn(true);
        when(ossStorageService.presignedUrl(anyString(), anyString(), any()))
                .thenReturn(Optional.of(SIGNED_URL));
    }
}
