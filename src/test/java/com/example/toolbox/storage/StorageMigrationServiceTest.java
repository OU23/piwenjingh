package com.example.toolbox.storage;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.Scope;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.dept.dto.DeptSaveDTO;
import com.example.toolbox.dept.service.DeptService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.tool.dto.ToolSaveDTO;
import com.example.toolbox.tool.entity.ToolDO;
import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.service.ToolWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存量迁移（M-22 / B13 验收 A10 · A11 · A12 · A19 · A20 · C5 · C6）。
 *
 * <p>关键口径（顺序不可颠倒）：**读本地 → 写 OSS → 改落点 → 删本地副本**。
 * 先删后改字段会造出「落点=local 但本地已无」→ 下载 404；崩在删本地之前最多残留一个本地文件，无数据丢失。
 *
 * <p>OSS 实现以 {@link MockBean} 替换（不打真实 OSS），本地实现走真实磁盘。
 */
class StorageMigrationServiceTest extends IntegrationTestBase {

    @Autowired
    private StorageMigrationService migrationService;
    @Autowired
    private ToolWriteService toolWriteService;
    @Autowired
    private DeptService deptService;
    @Autowired
    private LocalStorageService localStorageService;

    @MockBean
    private OssStorageService ossStorageService;

    private Long deptId;

    @BeforeEach
    void setUpData() {
        deptId = createDept();
        loginAs(999_999L, "admin", deptId, PermissionCode.SYS_SETTING, PermissionCode.TOOL_UPLOAD);
    }

    // ---------------- A10 正常路径（含"删本地副本"） ----------------

    @Test
    void migratesLocalFileThenSwitchesBackendAndDeletesLocalCopy() throws Exception {
        Persisted persisted = createTool("待迁移工具");
        when(ossStorageService.save(anyString(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MigrationTaskVO vo = migrationService.start(null, "admin");

        assertNotNull(vo.getTaskId(), "返回必须带 taskId（任务模型，异步化后不变）");
        assertEquals(MigrationStatus.COMPLETED.name(), vo.getStatus(), "本期同步执行：返回即已完成");
        assertEquals(1, vo.getTotal());
        assertEquals(1, vo.getMigrated());
        assertEquals(0, vo.getFailed());
        assertNotNull(vo.getStartedAt());

        assertEquals("oss", toolVersionMapper.selectById(persisted.versionId()).getStorageBackend(),
                "迁移成功后落点必须切换为 oss");
        assertFalse(localStorageService.exists(persisted.fileKey()),
                "★ 迁移成功后必须删除本地副本（否则磁盘不释放）");

        var size = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(ossStorageService).save(eq(persisted.fileKey()), any(InputStream.class), size.capture());
        assertEquals(persisted.size(), size.getValue().longValue(),
                "写入对象存储必须带**真实长度**（合并时总长已知）");
    }

    @Test
    void statusQueryReturnsSameSnapshot() throws Exception {
        createTool("可回查工具");
        when(ossStorageService.save(anyString(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MigrationTaskVO started = migrationService.start(null, "admin");
        MigrationTaskVO queried = migrationService.status(started.getTaskId());

        assertEquals(started.getTaskId(), queried.getTaskId());
        assertEquals(started.getMigrated(), queried.getMigrated());
        assertEquals(started.getTotal(), queried.getTotal());
    }

    // ---------------- A20 查询未命中 ----------------

    @Test
    void unknownTaskIdIsNotFound() {
        BusinessException ex = assertThrows(BusinessException.class, () -> migrationService.status("not-a-task"));
        assertEquals(Code.NOT_FOUND, ex.getCode(), "任务不存在 / 已过期 → 1004");
    }

    // ---------------- A11 幂等：不重复搬运 ----------------

    @Test
    void rerunDoesNotMoveTheSameFileAgain() throws Exception {
        Persisted persisted = createTool("幂等工具");
        when(ossStorageService.save(anyString(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        migrationService.start(null, "admin");
        MigrationTaskVO second = migrationService.start(null, "admin");

        assertEquals(0, second.getMigrated(), "第二次不得重复搬运已迁移的文件");
        verify(ossStorageService, times(1)).save(eq(persisted.fileKey()), any(InputStream.class), anyLong());
        assertEquals("oss", toolVersionMapper.selectById(persisted.versionId()).getStorageBackend());
    }

    /** limit 生效：分批迁移，可多次推进直至迁完 */
    @Test
    void limitBoundsSingleRun() throws Exception {
        Persisted first = createTool("批量-A");
        Persisted second = createTool("批量-B");
        when(ossStorageService.save(anyString(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MigrationTaskVO one = migrationService.start(1, "admin");
        assertEquals(1, one.getTotal(), "limit=1 时单次只处理 1 条");
        assertEquals(1, one.getMigrated());

        MigrationTaskVO rest = migrationService.start(null, "admin");
        assertEquals(1, rest.getTotal(), "剩余 1 条在下一轮处理");
        assertEquals("oss", toolVersionMapper.selectById(first.versionId()).getStorageBackend());
        assertEquals("oss", toolVersionMapper.selectById(second.versionId()).getStorageBackend(),
                "两轮之后应全部迁移完成（可重入）");
    }

    // ---------------- A12 / C6 失败隔离 ----------------

    @Test
    void singleFileFailureDoesNotAbortTheRest() throws Exception {
        Persisted bad = createTool("会失败");
        Persisted good = createTool("应成功");
        when(ossStorageService.save(anyString(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(ossStorageService.save(eq(bad.fileKey()), any(InputStream.class), anyLong()))
                .thenThrow(new BusinessException(Code.STORAGE_OSS_UNAVAILABLE, "对象存储暂不可用"));

        MigrationTaskVO vo = migrationService.start(null, "admin");

        assertEquals(2, vo.getTotal());
        assertEquals(1, vo.getMigrated(), "单条失败不得中断整体");
        assertEquals(1, vo.getFailed());
        assertEquals(1, vo.getFailures().size());
        assertEquals(String.valueOf(bad.versionId()), vo.getFailures().get(0).getVersionId(),
                "失败明细必须能定位到具体版本");
        assertNotNull(vo.getFailures().get(0).getReason());

        // 失败项：保持本地落点、本地文件仍在（仍可下载）——"迁移失败不影响可用性"
        assertEquals("local", toolVersionMapper.selectById(bad.versionId()).getStorageBackend());
        assertTrue(localStorageService.exists(bad.fileKey()), "迁移失败不得删除本地文件");

        // 成功项照常完成
        assertEquals("oss", toolVersionMapper.selectById(good.versionId()).getStorageBackend());
        assertFalse(localStorageService.exists(good.fileKey()));
    }

    /** C6：DB 记为 local 但本地文件已丢 → 记 failures，不中断其他 */
    @Test
    void missingLocalFileIsRecordedWithoutAborting() throws Exception {
        Persisted missing = createTool("本地已丢");
        localStorageService.delete(missing.fileKey()); // 造"DB 还记着本地、磁盘文件已丢"
        Persisted ok = createTool("正常");
        when(ossStorageService.save(anyString(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MigrationTaskVO vo = migrationService.start(null, "admin");

        assertEquals(1, vo.getFailed(), "文件缺失应记入 failures 而非抛异常中断");
        assertEquals(1, vo.getMigrated());
        assertEquals("local", toolVersionMapper.selectById(missing.versionId()).getStorageBackend(),
                "失败的条目不得误改为 oss（否则会出现「落点=oss 但对象不存在」）");
        assertEquals("oss", toolVersionMapper.selectById(ok.versionId()).getStorageBackend());
    }

    // ---------------- C5 软删工具的版本不迁移 ----------------

    @Test
    void softDeletedToolIsNotMigrated() {
        Persisted persisted = createTool("回收站里的工具");
        ToolDO tool = toolMapper.selectById(persisted.toolId());
        tool.setDeleted(1);
        toolMapper.updateById(tool);

        MigrationTaskVO vo = migrationService.start(null, "admin");

        assertEquals(0, vo.getTotal(), "软删工具的版本不得进入迁移候选");
        assertEquals("local", toolVersionMapper.selectById(persisted.versionId()).getStorageBackend());
        assertTrue(localStorageService.exists(persisted.fileKey()));
    }

    // ---------------- A19 可替换性（结构断言） ----------------

    @Test
    void migrationIsReplaceableByDesign() {
        assertTrue(MigrationExecutor.class.isInterface(), "必须存在执行器接口（换异步只换实现）");
        assertTrue(MigrationExecutor.class.isAssignableFrom(SyncMigrationExecutor.class),
                "本期同步执行器必须实现该接口");
        assertFalse(Modifier.isFinal(SyncMigrationExecutor.class.getModifiers()),
                "执行器不得为 final（否则无法替换/代理）");

        Set<String> states = Arrays.stream(MigrationStatus.values()).map(Enum::name).collect(Collectors.toSet());
        assertEquals(Set.of("PENDING", "RUNNING", "COMPLETED", "FAILED"), states,
                "任务状态机必须四态齐全（异步化后前端轮询依赖）");

        // 编排只依赖「任务存储 + 执行器接口」→ 不绑定同步实现
        Constructor<?>[] ctors = StorageMigrationService.class.getDeclaredConstructors();
        assertEquals(1, ctors.length);
        List<Class<?>> deps = Arrays.asList(ctors[0].getParameterTypes());
        assertTrue(deps.contains(MigrationTaskStore.class) && deps.contains(MigrationExecutor.class),
                "编排依赖必须是接口形态，实际：" + deps);
        assertFalse(deps.contains(SyncMigrationExecutor.class),
                "编排不得依赖具体执行器（否则换异步要改编排）");

        // A13 事务边界：文件 IO 不得在长事务内（原则 8）→ 迁移链路上不允许出现 @Transactional
        assertFalse(hasTransactional(SyncMigrationExecutor.class),
                "执行器不得使用 @Transactional（单文件一事务靠「搬完即单条 UPDATE」，不是跨文件大事务）");
        assertFalse(hasTransactional(StorageMigrationService.class),
                "编排不得使用 @Transactional（否则会把整轮迁移包成一个长事务）");
    }

    /** 类或其任一方法上是否标注了 {@code @Transactional} */
    private boolean hasTransactional(Class<?> type) {
        if (type.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)) {
            return true;
        }
        return Arrays.stream(type.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
    }

    // ---------------- 内部 ----------------

    private Long createDept() {
        loginAs(999_999L, "admin", null, PermissionCode.DEPT_MANAGE);
        DeptSaveDTO dto = new DeptSaveDTO();
        dto.setName("研发部");
        return Long.valueOf(deptService.create(dto));
    }

    /** 造一个工具（文件真实落盘；「文件已丢」由调用方再删物理文件模拟） */
    private Persisted createTool(String name) {
        String key = localStorageService.generateKey();
        byte[] content = new byte[64];
        try {
            localStorageService.save(key, new ByteArrayInputStream(content), content.length);
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
        dto.setFileName(name + ".bin");
        dto.setFileSize((long) content.length);
        Map<String, String> ids = toolWriteService.create(dto);

        Long versionId = Long.valueOf(ids.get("versionId"));
        ToolVersionDO version = toolVersionMapper.selectById(versionId);
        assertEquals("local", version.getStorageBackend(), "新建文件默认落本地（存量语义）");

        return new Persisted(Long.valueOf(ids.get("toolId")), versionId, key, content.length);
    }

    /** 一条已落库的测试数据（含落盘键与大小） */
    private record Persisted(Long toolId, Long versionId, String fileKey, int size) {
    }
}
