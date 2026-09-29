package com.example.toolbox.tool.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同名检查入参（契约 §9.4 ToolNameCheckDTO）。
 */
@Data
public class ToolNameCheckDTO {

    @NotBlank(message = "工具名称不能为空")
    private String name;
}
