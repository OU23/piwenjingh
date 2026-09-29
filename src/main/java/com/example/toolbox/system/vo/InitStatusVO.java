package com.example.toolbox.system.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 初始化状态 VO（契约 §十一）：{ initialized: boolean }。
 */
@Data
@AllArgsConstructor
public class InitStatusVO {

    private boolean initialized;
}
