package com.example.toolbox.excel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * xlsx 读取（契约 §6.2 解析规则）：
 * <ul>
 *   <li>只读**第 1 个 Sheet**，其余忽略；</li>
 *   <li>第 1 行 = 表头（**跳过**），数据自第 2 行起；**按列序号**取值（不按表头文字匹配）；</li>
 *   <li>**跳过全空行**（不计入 total）；字符串统一 {@code trim()}；</li>
 *   <li>行号取 **Excel 实际行号**（1-based，含表头行），保证错误明细里的行号用户能对上。</li>
 * </ul>
 */
public final class XlsxReader {

    private XlsxReader() {
    }

    /**
     * 读取数据行（不含表头行、不含全空行）。
     *
     * @param in              文件流（由调用方负责关闭）
     * @param headerRows      表头行数（模板固定为 1）
     * @param expectedColumns 模板列数（缺失列以空串补齐）
     * @throws BusinessException 文件损坏 / 非 xlsx 等**整体失败**情形 → {@code 1003}
     */
    public static List<XlsxRow> read(InputStream in, int headerRows, int expectedColumns) {
        List<XlsxRow> rows = new ArrayList<>();
        try {
            EasyExcel.read(in, new ReadListener<Map<Integer, String>>() {
                @Override
                public void invoke(Map<Integer, String> data, AnalysisContext context) {
                    // 0-based 行索引 → Excel 实际行号（表头为第 1 行）
                    int excelRowNum = context.readRowHolder().getRowIndex() + 1;
                    List<String> cells = new ArrayList<>(expectedColumns);
                    for (int i = 0; i < expectedColumns; i++) {
                        String value = data == null ? null : data.get(i);
                        cells.add(value == null ? "" : value.trim());
                    }
                    XlsxRow row = new XlsxRow(excelRowNum, cells);
                    if (!row.isBlank()) {
                        rows.add(row); // 全空行不计入 total
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // 无需处理
                }
            }).sheet(0).headRowNumber(headerRows).doRead();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(Code.PARAM_INVALID, "文件格式不正确，请使用下载的模板");
        }
        return rows;
    }
}
