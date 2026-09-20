package com.zhuri.coding.content.service.tag;

import com.zhuri.coding.model.article.dtos.TagCountDTO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.tag.pojos.ApTag;
import java.util.List;
import java.util.Map;

public interface TagService {

    /**
     * 查询标签列表
     * @param keyword 关键字
     * @return
     */
    List<ApTag> findList(String keyword);

    /**
     * 分类文章标签 TopN 聚合
     * @param categoryId 分类ID（频道ID）
     * @param keyword 标签名模糊过滤，null/空 表示不过滤
     * @param size TopN 条数
     * @return 标签名与数量的聚合列表（非 null）
     */
    List<TagCountDTO> topByCategory(Integer categoryId, String keyword, int size);

    /**
     * 查询指定分类下文章使用的标签及其数量
     * @param categoryId 分类ID（频道ID）
     * @return 标签名和文章数的列表
     */
    List<Map<String, Object>> findTagsByCategory(Integer categoryId);

    /**
     * 分页查询某个标签下的文章列表（JSON_CONTAINS 匹配 ap_article.tags）
     * @param tagName 标签名
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @param sort 排序方式：hot-热门、latest-最新、hottest-最热
     * @return {total, page, size, list}，list 项复用文章列表 null-safe 结构
     */
    ResponseResult getArticles(String tagName, Integer page, Integer size, String sort);

    /**
     * 标签搜索：按标签名 LIKE 分页查询启用(1)中的标签
     * @param keyword 标签名关键词，null/空 表示不过滤
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return okResult(list)，list 项含 id/title(=name)/name/category 等字段
     */
    ResponseResult search(String keyword, Integer page, Integer size);
}