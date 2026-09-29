package com.example.toolbox.setting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局设置 DO（数据模型 §3.8 tb_setting）。键值结构。
 */
@Data
@TableName("tb_setting")
public class SettingDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键，唯一 */
    private String settingKey;

    /** 配置值（字符串存储） */
    private String settingValue;

    private LocalDateTime updateTime;
}
