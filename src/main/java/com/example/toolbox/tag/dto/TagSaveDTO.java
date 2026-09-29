package com.example.toolbox.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 标签保存入参（契约 §七 TagSaveDTO）。
 * name 必填、≤20 字符、不可重复。
 */
@Data
public class TagSaveDTO {

    @NotBlank(message = "标签名称不能为空")
    @Size(max = 20, message = "标签名称不能超过 20 字符")
    private String name;
}
