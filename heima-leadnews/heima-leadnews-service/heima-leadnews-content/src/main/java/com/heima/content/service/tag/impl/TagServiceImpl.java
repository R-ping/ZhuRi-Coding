package com.heima.content.service.tag.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.tag.TagMapper;
import com.heima.content.service.tag.TagService;
import com.heima.model.article.dtos.TagCountDTO;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.tag.pojos.ApTag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class TagServiceImpl extends ServiceImpl<TagMapper, ApTag> implements TagService {

    @Autowired
    private ApArticleMapper apArticleMapper;

    /**
     * 查询标签列表
     * @param keyword 关键字
     * @return
     */
    @Override
    public List<ApTag> findList(String keyword) {
        LambdaQueryWrapper<ApTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApTag::getStatus, 1);
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(ApTag::getName, keyword.trim());
        }
        wrapper.orderByAsc(ApTag::getSort);
        return list(wrapper);
    }

    @Override
    public List<Map<String, Object>> findTagsByCategory(Integer categoryId) {
        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticle::getChannelId, categoryId)
               .eq(ApArticle::getStatus, (byte) 9);
        List<ApArticle> articles = apArticleMapper.selectList(wrapper);

        Map<String, Integer> tagCountMap = new HashMap<>();
        for (ApArticle article : articles) {
            List<String> tags = article.getTags();
            if (tags != null && !tags.isEmpty()) {
                for (String tag : tags) {
                    if (tag != null && !tag.trim().isEmpty()) {
                        tagCountMap.merge(tag.trim(), 1, Integer::sum);
                    }
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : tagCountMap.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("tagName", entry.getKey());
            item.put("count", entry.getValue());
            result.add(item);
        }

        result.sort((a, b) -> (Integer) b.get("count") - (Integer) a.get("count"));
        return result;
    }

    /**
     * 分页查询某个标签下的文章列表，复用文章列表 null-safe 结构
     * @param tagName 标签名
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @param sort hot-热门 latest-最新 hottest-最热
     */
    @Override
    public ResponseResult getArticles(String tagName, Integer page, Integer size, String sort) {
        // 参数校验与兜底
        String safeTag = tagName == null ? "" : tagName.trim();
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (size == null || size < 1) ? 20 : Math.min(size, 50);
        String safeSort = sort;
        if (!"hot".equals(safeSort) && !"latest".equals(safeSort) && !"hottest".equals(safeSort)) {
            safeSort = "hot";
        }

        int offset = (safePage - 1) * safeSize;
        List<ApArticle> articles = safeTag.isEmpty()
                ? new ArrayList<>()
                : apArticleMapper.selectTagArticleList(safeTag, safeSort, offset, safeSize);
        Long total = safeTag.isEmpty() ? 0L : apArticleMapper.countTagArticles(safeTag);

        // 复用 nullSafeToMap 保证返回字段非 null（字符串""、数值0或原值）
        List<Map<String, Object>> list = articles.stream()
                .map(ApArticle::nullSafeToMap)
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("total", total != null ? total.longValue() : 0L);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("list", list);
        return ResponseResult.okResult(result);
    }

    /**
     * 标签搜索：按标签名 LIKE 分页查询启用(1)中的标签
     * @param keyword 标签名关键词
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return okResult(list)，list 项含 id/title(=name)/name/category 等字段
     */
    @Override
    public ResponseResult search(String keyword, Integer page, Integer size) {
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (size == null || size < 1) ? 10 : Math.min(size, 50);

        IPage<ApTag> iPage = new Page<>(safePage, safeSize);
        LambdaQueryWrapper<ApTag> wrapper = new LambdaQueryWrapper<>();
        // 仅查询启用(1)状态的标签
        wrapper.eq(ApTag::getStatus, 1);
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(ApTag::getName, keyword.trim());
        }
        wrapper.orderByAsc(ApTag::getSort);

        IPage<ApTag> resultPage = page(iPage, wrapper);

        // 组装标签字段（title=name 以对齐前端搜索展示的标题字段）
        List<Map<String, Object>> list = new ArrayList<>();
        for (ApTag tag : resultPage.getRecords()) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", tag.getId());
            item.put("title", tag.getName());
            item.put("name", tag.getName());
            item.put("category", tag.getCategory());
            item.put("postArticleCount", tag.getPostArticleCount());
            item.put("concernUserCount", tag.getConcernUserCount());
            list.add(item);
        }
        return ResponseResult.okResult(list);
    }

    /**
     * 分类文章标签 TopN 聚合，返回必要非 null（空用空列表）
     * @param categoryId 分类ID（频道ID）
     * @param keyword 标签名模糊过滤，null/空 表示不过滤
     * @param size TopN 条数，小于等于 0 时兜底为 15
     */
    @Override
    public List<TagCountDTO> topByCategory(Integer categoryId, String keyword, int size) {
        if (categoryId == null) return new ArrayList<>();
        if (size <= 0) size = 15;
        return apArticleMapper.selectTopTagsByCategory(categoryId, keyword, size);
    }
}