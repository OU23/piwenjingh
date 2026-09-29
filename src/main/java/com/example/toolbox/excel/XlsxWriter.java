package com.example.toolbox.excel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.metadata.WriteSheet;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * xlsx 写出（模板 / 错误明细 / 导出列表）。
 * 表头统一用 {@code List<List<String>>}（外层为列），由 {@link #headOf(List)} 从列名列表构造。
 */
public final class XlsxWriter {

    private XlsxWriter() {
    }

    /** 列名列表 → EasyExcel 表头结构（外层=列，单层表头） */
    public static List<List<String>> headOf(List<String> columns) {
        List<List<String>> head = new ArrayList<>(columns.size());
        for (String column : columns) {
            head.add(List.of(column));
        }
        return head;
    }

    /** 写出为字节数组（用于错误 Excel 的 Base64 内联） */
    public static byte[] toBytes(String sheetName, List<List<String>> head, List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, sheetName, head, rows);
        return out.toByteArray();
    }

    /**
     * 写出到流（用于文件流响应）。
     * {@code autoCloseStream(false)}：不代为关闭调用方的流，由上层（如 Servlet 响应）自行收尾。
     */
    public static void write(OutputStream out, String sheetName,
                             List<List<String>> head, List<List<String>> rows) {
        com.alibaba.excel.ExcelWriter writer = EasyExcel.write(out).autoCloseStream(false).build();
        try {
            WriteSheet sheet = EasyExcel.writerSheet(0, sheetName).head(head).build();
            writer.write(rows, sheet);
        } finally {
            writer.finish();
        }
    }
}
