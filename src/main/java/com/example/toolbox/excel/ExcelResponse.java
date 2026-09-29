package com.example.toolbox.excel;

import com.example.toolbox.common.util.ContentDispositionUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

import java.io.IOException;

/**
 * Excel 文件流响应封装（契约 §6.2 §4.2）：
 * HTTP 200 + xlsx {@code Content-Type} + {@code Content-Disposition} 双写（支持中文名）；
 * **不使用统一响应体**（失败时另由全局异常处理器返回 JSON）。
 */
public final class ExcelResponse {

    private ExcelResponse() {
    }

    /** 写出字节数组形式的 Excel（模板 / 导出） */
    public static void write(HttpServletResponse response, byte[] bytes, String fileName) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(ExcelConstants.XLSX_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtils.attachment(fileName));
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }
}
