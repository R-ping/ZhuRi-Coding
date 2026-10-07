package com.zhuri.coding.model.admin.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Banner 新建 / 编辑入参。
 *
 * <p>时间窗是**字符串**（{@code yyyy-MM-dd HH:mm:ss}）而不是 {@code Date}：
 * 与 {@code AdminActivitySaveDto} 同一取舍 —— DATE/DATETIME 列经 Jackson 反序列化
 * 要过一层时区换算，"差一小时"这类偏差在生效时间上极难发现。
 * 收成字符串由服务端用 STRICT 解析显式换算，格式不对给一句能照着改的错误。
 *
 * <p>两个时间都可空：可空的组合是"只开始"（到点自动上线、手动下线）与"只结束"
 * （立即生效、到点自动下线）—— 都合法；结束早于开始才报错。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminBannerSaveDto extends AdminActionDto {

    private static final long serialVersionUID = 1L;

    /** 运营备注名（必填；上限 100，与 {@code ap_banner.title} 等长） */
    private String title;

    /** 图片地址（必填；上限 500） */
    private String imageUrl;

    /** 跳转地址（必填；站内路由 {@code /} 开头或 http(s) 外链，其他协议被拒） */
    private String linkUrl;

    /** 展示顺序，小的在前（0-999；不传按 0） */
    private Integer sortOrder;

    /** 生效开始；可空 = 不限，{@code yyyy-MM-dd HH:mm:ss} */
    private String startTime;

    /** 生效结束；可空 = 不限，{@code yyyy-MM-dd HH:mm:ss} */
    private String endTime;
}
