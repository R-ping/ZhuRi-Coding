package com.zhuri.coding.model.ops.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * C 端 · 轮播 Banner 出参。
 *
 * <p>只有三样用户需要的东西：<b>运营备注名 {@code title} 刻意不下发</b> ——
 * 它是运营内部辨认素材用的（"这条是谁、投的什么"），不是展示文案，
 * 图上有没有字是设计的事，把备注名漏给用户只会成为穿帮镜头。
 */
@Data
public class BannerVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String imageUrl;

    private String linkUrl;
}
