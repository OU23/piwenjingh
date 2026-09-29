package com.example.toolbox.common.result;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误码枚举完整性：契约 §十二 实际 14 个（比部分计划文案的 15 少一个，沿用 P5 勘误）。
 */
class CodeTest {

    private static final List<Integer> CONTRACT_CODES =
            List.of(1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 2001, 2002, 2003, 2004, 2005);

    @Test
    void allContractCodesPresent() {
        for (int c : CONTRACT_CODES) {
            Code code = Code.fromCode(c);
            assertNotNull(code, "缺失契约错误码 " + c);
            assertTrue(code.getMessage() != null && !code.getMessage().isBlank(),
                    "错误码 " + c + " 缺少 message");
        }
    }

    @Test
    void httpStatusMapping() {
        assertEquals(500, Code.SYS_ERROR.getHttpStatus());
        assertEquals(401, Code.UNAUTHENTICATED.getHttpStatus());
        assertEquals(403, Code.FORBIDDEN.getHttpStatus());
        for (int c : List.of(1003, 1004, 1005, 1006, 1007, 2001, 2002, 2003, 2004, 2005)) {
            assertEquals(200, Code.fromCode(c).getHttpStatus(), "错误码 " + c + " 应为 200");
        }
    }

    @Test
    void noDuplicateCodes() {
        long distinct = Arrays.stream(Code.values()).map(Code::getCode).distinct().count();
        assertEquals(Code.values().length, distinct, "错误码不允许重复");
    }
}
