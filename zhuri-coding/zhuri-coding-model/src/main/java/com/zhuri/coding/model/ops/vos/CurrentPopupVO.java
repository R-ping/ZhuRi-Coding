package com.zhuri.coding.model.ops.vos;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * C 端 · 当前弹窗出参（最多一条）。
 *
 * <p>{@code buttonText} 为 null 时前端只渲染"知道了"关闭按钮。
 * {@code endTime} 下发给前端用于倒计时类展示（可不用）。
 */
@Data
public class CurrentPopupVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    private String content;

    private String imageUrl;

    private String buttonText;

    private String linkUrl;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date endTime;
}
