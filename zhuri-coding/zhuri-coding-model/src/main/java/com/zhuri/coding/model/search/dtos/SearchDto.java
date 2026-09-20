package com.zhuri.coding.model.search.dtos;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 统一搜索请求 DTO（对齐掘金风格：单一端点按 id_type 区分分栏）。
 *
 * <p>入参示例（兼容 camelCase，也接受掘金式下划线 id_type/sort_type）：
 * <pre>
 * {
 *   "query": "智谱",
 *   "idType": 1,
 *   "pageNum": 1,
 *   "pageSize": 20,
 *   "sortType": 0
 * }
 * </pre>
 * </p>
 */
@Data
public class SearchDto {

    /** 搜索关键字 */
    private String query;

    /**
     * 搜索类型：0 综合 / 1 文章 / 2 课程 / 3 标签 / 4 用户
     * 兼容掘金传参 id_type（同时接受 idType）
     */
    @JsonAlias("id_type")
    private Integer idType;

    /** 当前页（从 1 开始） */
    private int pageNum;

    /** 每页条数（默认 20） */
    private int pageSize;

    /**
     * 排序类型：0 综合 / 1 最新 / 2 最热（仅文章分栏生效，预留）
     * 兼容掘金传参 sort_type（同时接受 sortType）
     */
    @JsonAlias("sort_type")
    private Integer sortType;
}