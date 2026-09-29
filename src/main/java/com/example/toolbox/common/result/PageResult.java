package com.example.toolbox.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页统一返回结构（契约 §1.2）：list / total / pageNum / pageSize。
 */
@Data
public class PageResult<T> implements Serializable {

    private List<T> list;
    private long total;
    private long pageNum;
    private long pageSize;

    public static <T> PageResult<T> of(List<T> list, long total, long pageNum, long pageSize) {
        PageResult<T> r = new PageResult<>();
        r.setList(list);
        r.setTotal(total);
        r.setPageNum(pageNum);
        r.setPageSize(pageSize);
        return r;
    }
}
