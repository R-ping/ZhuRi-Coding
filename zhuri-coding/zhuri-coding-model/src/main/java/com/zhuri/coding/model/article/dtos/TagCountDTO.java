package com.zhuri.coding.model.article.dtos;

import lombok.Data;

/**
 * 分类文章标签 TopN 聚合结果 DTO
 * 用于 /api/v1/tag/category-top 接口返回，保证字段非 null（字符串 ""、数值 0）。
 */
@Data
public class TagCountDTO {

    // 标签名（默认空字符串，保证非 null）
    String tagName = "";
    // 该标签在该分类下的文章数量（默认 0，保证非 null）
    Integer count = 0;
}