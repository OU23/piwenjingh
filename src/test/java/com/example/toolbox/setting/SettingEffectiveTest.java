package com.example.toolbox.setting;

import com.example.toolbox.common.constant.PermissionCode;
import com.example.toolbox.common.constant.SettingKey;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import com.example.toolbox.setting.service.SettingService;
import com.example.toolbox.storage.StorageService;
import com.example.toolbox.support.IntegrationTestBase;
import com.example.toolbox.upload.dto.UploadMergeDTO;
import com.example.toolbox.upload.service.UploadService;
import com.example.toolbox.upload.vo.UploadMergeVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置**生效性**（B4 验收 A3 / B1）。
 *
 * <p>本类专门钉住两个"改了就必须真生效"的口径 —— 它们在实现上最容易假生效：
 * <ol>
 *   <li><b>单文件上限</b>：改小后，**新上传**按新值校验（超限 → 2001），无需重启；</li>
 *   <li><b>存储根路径</b>：改后**新文件落到新目录**（契约 §十"只在**新文件落盘**时生效"）。
 *       此前存储层只读配置项，改设置不生效 —— 本类就是防它回退。</li>
 * </ol>
 */
class SettingEffectiveTest extends IntegrationTestBase {

    @Autowired
    private SettingService settingService;
    @Autowired
    private StorageService storageService;
    @Autowired
    private UploadService uploadService;

    @TempDir
    Path tempDir;

    /** A3：上限改小后，新上传超新上限 → 2001（提示含新上限），已入库文件不受影响 */
    @Test
    void changedLimitAppliesToNewUploads() {
        loginAs(999_999L, "admin", 1L, PermissionCode.TOOL_UPLOAD);

        // 先按默认上限成功上传一个 8 字节文件（已入库文件）
        uploadService.uploadChunk("tbf-limit-a", 1, 1, "a.bin",
                new ByteArrayInputStream(new byte[8]));
        UploadMergeVO first = uploadService.merge(mergeDto("tbf-limit-a", 1, 8));
        assertTrue(storageService.exists(first.getFileKey()), "默认上限下应能上传成功");

        // 改小上限到 4 字节
        settingService.putValue(SettingKey.MAX_FILE_SIZE, "4");
        assertEquals(4L, settingService.getMaxFileSize(), "设置应已更新（保存即生效）");

        // 新上传 8 字节 → 2001，且 message 含新上限数值
        uploadService.uploadChunk("tbf-limit-b", 1, 1, "b.bin",
                new ByteArrayInputStream(new byte[8]));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> uploadService.merge(mergeDto("tbf-limit-b", 1, 8)));
        assertEquals(Code.FILE_TOO_LARGE, ex.getCode(), "超新上限应返回 2001");
        assertTrue(ex.getMessage().contains("4"), "message 应含新上限，实际：" + ex.getMessage());

        // 已入库文件不受影响：仍可读、大小不变
        assertTrue(storageService.exists(first.getFileKey()), "已入库文件不得受影响");
        assertEquals(8, sizeOf(first.getFileKey()), "已入库文件大小不得变化");

        // 收尾：清掉被 2001 拦下的那次上传残留的分片（不留中间文件给后续用例）
        uploadService.cancel("tbf-limit-b");
    }

    /** A3 / B1：改存储根路径后，**新文件落到新目录**（而不是旧目录） */
    @Test
    void changedStorageRootTakesEffectForNewFiles() throws Exception {
        Path oldRoot = tempDir.resolve("old-root");
        Path newRoot = tempDir.resolve("new-root");
        Files.createDirectories(oldRoot);
        Files.createDirectories(newRoot);

        loginAs(999_999L, "admin", 1L, PermissionCode.TOOL_UPLOAD);

        // ① 根路径指向 old-root → 文件应落在 old-root
        settingService.putValue(SettingKey.STORAGE_ROOT, oldRoot.toString());
        String keyInOld = storageService.save(storageService.generateKey(),
                new ByteArrayInputStream(new byte[4]), 4);
        assertTrue(Files.exists(oldRoot.resolve(keyInOld)), "应落在旧目录");
        assertTrue(storageService.exists(keyInOld), "存储层应能按当前根路径找到它");

        // ② 切换到 new-root → **新**文件落在 new-root
        settingService.putValue(SettingKey.STORAGE_ROOT, newRoot.toString());
        String keyInNew = storageService.save(storageService.generateKey(),
                new ByteArrayInputStream(new byte[4]), 4);
        assertTrue(Files.exists(newRoot.resolve(keyInNew)),
                "改设置后新文件必须落到新目录（A3/B1）");
        assertTrue(Files.exists(oldRoot.resolve(keyInOld)),
                "旧文件不迁移、也不得被误删（契约 §十：不自动迁移）");

        // ③ 旧根路径下的文件在切换后不再可见 —— 这是"需人工迁移"的技术事实，故前端必须提示
        assertTrue(Files.exists(oldRoot.resolve(keyInOld)) && !newRoot.resolve(keyInOld).toFile().exists(),
                "旧文件留在旧目录");
    }

    // ---------------- 内部 ----------------

    private long sizeOf(String fileKey) {
        try {
            return storageService.size(fileKey);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UploadMergeDTO mergeDto(String identifier, int totalChunks, long size) {
        UploadMergeDTO dto = new UploadMergeDTO();
        dto.setIdentifier(identifier);
        dto.setFileName("a.bin");
        dto.setFileSize(size);
        dto.setTotalChunks(totalChunks);
        return dto;
    }
}
