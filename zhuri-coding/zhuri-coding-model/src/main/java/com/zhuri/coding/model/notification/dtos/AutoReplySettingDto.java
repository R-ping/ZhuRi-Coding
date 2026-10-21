package com.zhuri.coding.model.notification.dtos;

import lombok.Data;

/**
 * 关注自动私信设置：保存请求体。
 * <p>两个字段都可单独提交：只改开关时 content 传 null，只改文案时 enabled 传 null。
 */
@Data
public class AutoReplySettingDto {

    /** 开关；null 表示本次不修改 */
    private Boolean enabled;

    /** 文案；null 表示本次不修改 */
    private String content;
}
