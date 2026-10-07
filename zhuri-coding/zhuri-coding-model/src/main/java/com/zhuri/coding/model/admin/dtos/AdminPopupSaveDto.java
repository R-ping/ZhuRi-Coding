package com.zhuri.coding.model.admin.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 弹窗新建 / 编辑入参。
 *
 * <p>时间窗**必填**且结束不得早于开始 —— 与 {@link AdminBannerSaveDto} 的可空相反：
 * 弹窗是投放语义（到点出现、到点消失），"永久弹窗"等于骚扰，且用户关闭记录的
 * Redis TTL 上限就取结束时间，没有截止就没有 TTL。
 *
 * <p>{@code content} 只收纯文本：弹窗不是富文本场景，收 HTML 就要为运营粘进来的
 * 任意标记在 C 端的渲染安全负责。按钮可空 —— 可空时前端只渲染"知道了"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminPopupSaveDto extends AdminActionDto {

    private static final long serialVersionUID = 1L;

    /** 弹窗标题（必填；上限 100） */
    private String title;

    /** 正文纯文本（可空，纯图弹窗；上限 2000） */
    private String content;

    /** 配图地址（可空；上限 500） */
    private String imageUrl;

    /** 动作按钮文案（可空；上限 50，可空时前端只渲染"知道了"） */
    private String buttonText;

    /** 动作按钮跳转（buttonText 非空时建议填写；站内 {@code /} 开头或 http(s) 外链） */
    private String linkUrl;

    /** 生效开始（必填，{@code yyyy-MM-dd HH:mm:ss}） */
    private String startTime;

    /** 生效结束（必填，且不得早于开始） */
    private String endTime;
}
