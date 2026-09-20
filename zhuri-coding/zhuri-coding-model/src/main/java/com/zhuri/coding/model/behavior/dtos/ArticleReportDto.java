package com.zhuri.coding.model.behavior.dtos;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 文章举报请求参数
 */
@Data
public class ArticleReportDto {

    /** 举报原因（必填） */
    private String reason;

    /** 补充说明（选填，≤100字） */
    private String description = "";

    /** 举报图片URL列表（选填，最多4张） */
    private List<String> imageUrls = new ArrayList<>();
}
