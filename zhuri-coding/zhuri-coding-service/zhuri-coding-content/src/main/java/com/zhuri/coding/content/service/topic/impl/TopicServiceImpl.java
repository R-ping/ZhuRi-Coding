package com.zhuri.coding.content.service.topic.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.circle.ApCircleMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.mapper.topic.TopicCircleRelationMapper;
import com.zhuri.coding.content.mapper.topic.TopicMapper;
import com.zhuri.coding.content.mapper.topic.TopicRelationMapper;
import com.zhuri.coding.content.mapper.topic.UserTopicPostMapper;
import com.zhuri.coding.content.service.topic.TopicService;
import com.zhuri.coding.model.circle.pojos.ApCircle;
import com.zhuri.coding.model.topic.dtos.TopicSquareDto;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.topic.pojos.ApTopic;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.TopicCircleRelation;
import com.zhuri.coding.model.article.pojos.TopicRelation;
import com.zhuri.coding.model.topic.vos.TopicDetailVO;
import com.zhuri.coding.model.topic.vos.TopicRecommendVO;
import com.zhuri.coding.model.topic.vos.TopicSquareVO;
import com.zhuri.coding.utils.common.PageParamUtil;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TopicServiceImpl extends ServiceImpl<TopicMapper, ApTopic> implements TopicService {

    @Autowired
    private TopicMapper topicMapper;

    @Autowired
    private TopicRelationMapper topicRelationMapper;

    @Autowired
    private UserTopicPostMapper userTopicPostMapper;

    @Autowired
    private TopicCircleRelationMapper topicCircleRelationMapper;

    @Autowired
    private ApCircleMapper apCircleMapper;

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Override
    public Map<String, Object> recommend(int page, int size) {
        // 该接口在网关公开只读白名单内（未登录可达），且下方按 size 逐元素构造集合，
        // 必须先把 size 收口到上限，否则 size=Integer.MAX_VALUE 会直接耗尽内存。
        // 注意：本方法是**环形缓冲**（page 从 0 开始，offset = page*size % total），
        // 故 page 只能归一为非负，不能像 1-based 分页那样抬到 1 —— 否则默认的 page=0 会跳过第一页。
        if (page < 0) {
            page = 0;
        }
        size = PageParamUtil.normalizeSize(size);
        // 查询所有推荐话题
        LambdaQueryWrapper<ApTopic> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApTopic::getIsRecommend, 1)
               .eq(ApTopic::getStatus, 1)
               .orderByAsc(ApTopic::getRecommendSort);
        List<ApTopic> allTopics = list(wrapper);
        int total = allTopics.size();
        if (total == 0) {
            Map<String, Object> result = new HashMap<>();
            result.put("list", new ArrayList<>());
            result.put("total", 0);
            result.put("page", page);
            return result;
        }
        // 环形缓冲：offset = (page * size) % total
        // 用 long 做乘法再取模：page 很大时 (page * size) 会 int 溢出为负，
        // 进而 allTopics.get(负数) 抛 IndexOutOfBoundsException
        int offset = (int) (((long) page * size) % total);
        List<ApTopic> pageTopics = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            int idx = (offset + i) % total;
            pageTopics.add(allTopics.get(idx));
        }
        // 转换为 VO
        List<TopicRecommendVO> voList = pageTopics.stream().map(t -> {
            TopicRecommendVO vo = new TopicRecommendVO();
            vo.setId(t.getId());
            vo.setName(t.getName());
            vo.setBadge(t.getBadge() != null ? t.getBadge() : "");
            vo.setParticipantCount(t.getParticipantCount() != null ? t.getParticipantCount() : 0L);
            vo.setViewCount(t.getViewCount() != null ? t.getViewCount() : 0L);
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("list", voList);
        result.put("total", total);
        result.put("page", page);
        return result;
    }

    @Override
    public Map<String, Object> square(TopicSquareDto dto) {
        LambdaQueryWrapper<ApTopic> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApTopic::getStatus, 1);
        if (dto.getKeyword() != null && !dto.getKeyword().trim().isEmpty()) {
            wrapper.like(ApTopic::getName, dto.getKeyword().trim());
        }
        // 排序
        String sort = dto.getSort() != null ? dto.getSort() : "hot";
        if ("hot".equals(sort)) {
            wrapper.orderByDesc(ApTopic::getViewCount);
        } else {
            wrapper.orderByDesc(ApTopic::getPostCount);
        }
        long cursor = dto.getCursor() != null ? dto.getCursor() : 0L;
        if (cursor < 0) {
            cursor = 0L;
        }
        // size 是 Integer：客户端显式传 "size": null 时不能直接拆箱（会 NPE）；同时收口上限防一次拉全表
        Integer rawSize = dto.getSize();
        int size = PageParamUtil.normalizeSize(rawSize == null ? 20 : rawSize);
        int pageNum = (int) (cursor / size) + 1;
        Page<ApTopic> pageParam = new Page<>(pageNum, size + 1);
        IPage<ApTopic> pageResult = topicMapper.selectPage(pageParam, wrapper);
        List<ApTopic> topics = pageResult.getRecords();
        boolean hasMore = topics.size() > size;
        if (hasMore) {
            topics = topics.subList(0, size);
        }
        List<TopicSquareVO> voList = topics.stream().map(t -> {
            TopicSquareVO vo = new TopicSquareVO();
            vo.setId(t.getId());
            vo.setName(t.getName());
            vo.setDescription(t.getDescription() != null ? t.getDescription() : "");
            vo.setParticipantCount(t.getParticipantCount() != null ? t.getParticipantCount() : 0L);
            vo.setViewCount(t.getViewCount() != null ? t.getViewCount() : 0L);
            vo.setPostCount(t.getPostCount() != null ? (long) t.getPostCount() : 0L);
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("list", voList);
        result.put("cursor", cursor + size);
        result.put("has_more", hasMore);
        return result;
    }

    @Override
    public TopicDetailVO detail(Long id) {
        ApTopic topic = getById(id);
        if (topic == null) {
            return null;
        }
        TopicDetailVO vo = new TopicDetailVO();
        vo.setId(topic.getId());
        vo.setName(topic.getName());
        vo.setDescription(topic.getDescription() != null ? topic.getDescription() : "");
        vo.setCoverImage(topic.getCoverImage() != null ? topic.getCoverImage() : "");
        vo.setBadge(topic.getBadge() != null ? topic.getBadge() : "");
        vo.setType(topic.getType() != null ? topic.getType() : 1);
        // 浏览数为关联沸点浏览量 + 文章浏览量总和，参与数为关联沸点数 + 文章数（统一用“参与”表示帖子数量）
        long pinCount = 0L;
        long pinViews = 0L;
        LambdaQueryWrapper<ApPins> pinsWrapper = new LambdaQueryWrapper<>();
        pinsWrapper.eq(ApPins::getTopicId, id)
                   .eq(ApPins::getStatus, (byte) 9)
                   .eq(ApPins::getIsDeleted, false);
        List<ApPins> topicPins = apPinsMapper.selectList(pinsWrapper);
        if (topicPins != null) {
            pinCount = topicPins.size();
            for (ApPins p : topicPins) {
                pinViews += p.getViews() != null ? p.getViews() : 0L;
            }
        }
        long articleCount = 0L;
        long articleViews = 0L;
        LambdaQueryWrapper<TopicRelation> articleRelWrapper = new LambdaQueryWrapper<>();
        articleRelWrapper.eq(TopicRelation::getTopicId, id)
                         .eq(TopicRelation::getTargetType, 1);
        List<TopicRelation> articleRelations = topicRelationMapper.selectList(articleRelWrapper);
        if (articleRelations != null && !articleRelations.isEmpty()) {
            List<Long> articleIds = articleRelations.stream()
                    .map(TopicRelation::getTargetId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (!articleIds.isEmpty()) {
                LambdaQueryWrapper<ApArticle> articleWrapper = new LambdaQueryWrapper<>();
                articleWrapper.in(ApArticle::getId, articleIds)
                              .eq(ApArticle::getStatus, (byte) 9)
                              .eq(ApArticle::getIsDeleted, false);
                List<ApArticle> topicArticles = apArticleMapper.selectList(articleWrapper);
                if (topicArticles != null) {
                    articleCount = topicArticles.size();
                    for (ApArticle a : topicArticles) {
                        articleViews += a.getViews() != null ? a.getViews() : 0L;
                    }
                }
            }
        }
        vo.setViewCount(pinViews + articleViews);
        vo.setParticipantCount(pinCount + articleCount);
        vo.setPostCount(pinCount + articleCount);
        // availableTabs 根据 type 返回
        List<String> tabs = new ArrayList<>();
        tabs.add("hot");
        tabs.add("new");
        if (topic.getType() != null && topic.getType() == 2) {
            tabs.add("article");
            tabs.add("pin");
        } else {
            tabs.add("pin");
        }
        vo.setAvailableTabs(tabs);
        // 关联圈子
        LambdaQueryWrapper<TopicCircleRelation> relWrapper = new LambdaQueryWrapper<>();
        relWrapper.eq(TopicCircleRelation::getTopicId, id);
        List<TopicCircleRelation> relations = topicCircleRelationMapper.selectList(relWrapper);
        List<TopicDetailVO.TopicCircleInfo> circleInfos = new ArrayList<>();
        for (TopicCircleRelation rel : relations) {
            TopicDetailVO.TopicCircleInfo info = new TopicDetailVO.TopicCircleInfo();
            info.setCircleId(rel.getCircleId());
            // 查询圈子名称
            ApCircle circle = apCircleMapper.selectById(rel.getCircleId());
            info.setCircleName(circle != null ? circle.getName() : "");
            circleInfos.add(info);
        }
        vo.setCircleInfo(circleInfos);
        return vo;
    }

    @Override
    public Map<String, Object> feed(Long id, String tab, long cursor, int size) {
        Map<String, Object> result = new HashMap<>();
        // size 由客户端直传：为 0 时下方 cursor / size 会除零（500），同时需收口上限防一次拉全表
        size = PageParamUtil.normalizeSize(size);
        if (cursor < 0) {
            cursor = 0L;
        }
        // 文章分栏：前端传 article_hot（热门）/article_new（最新），兼容旧值 article
        boolean isArticle = tab != null && (tab.startsWith("article"));
        if (isArticle) {
            return articleFeed(id, tab, cursor, size);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        boolean hasMore = false;
        // 沸点：从 ap_pins 查 topic_id，热门按点赞、最新按时间
        int pageNum = (int) (cursor / size) + 1;
        Page<ApPins> pinsPage = new Page<>(pageNum, size + 1);
        LambdaQueryWrapper<ApPins> pinsWrapper = new LambdaQueryWrapper<>();
        pinsWrapper.eq(ApPins::getTopicId, id)
                   .eq(ApPins::getStatus, (byte) 9);
        if ("hot".equals(tab)) {
            pinsWrapper.orderByDesc(ApPins::getLikes);
        } else {
            pinsWrapper.orderByDesc(ApPins::getCreatedTime);
        }
        IPage<ApPins> pinsPageResult = apPinsMapper.selectPage(pinsPage, pinsWrapper);
        List<ApPins> pinsList = pinsPageResult.getRecords();
        hasMore = pinsList.size() > size;
        if (hasMore) pinsList = pinsList.subList(0, size);
        for (ApPins pin : pinsList) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", pin.getId());
            item.put("userId", pin.getUserId());
            item.put("userName", pin.getUserName() != null ? pin.getUserName() : "");
            item.put("userAvatar", pin.getUserAvatar() != null ? pin.getUserAvatar() : "");
            item.put("content", pin.getContent());
            item.put("likeCount", pin.getLikes());
            item.put("commentCount", pin.getComment());
            item.put("createdTime", pin.getCreatedTime());
            item.put("type", "pin");
            list.add(item);
        }
        result.put("list", list);
        result.put("cursor", cursor + size);
        result.put("has_more", hasMore);
        return result;
    }

    /**
     * 话题文章 Feed：返回完整文章卡片数据。
     * 排序：article_hot 按阅读量降序，article_new（或 article）按发布时间降序。
     */
    private Map<String, Object> articleFeed(Long id, String tab, long cursor, int size) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        // 查询话题关联的文章 targetId 列表
        LambdaQueryWrapper<TopicRelation> relWrapper = new LambdaQueryWrapper<>();
        relWrapper.eq(TopicRelation::getTopicId, id)
                  .eq(TopicRelation::getTargetType, 1);
        List<TopicRelation> relations = topicRelationMapper.selectList(relWrapper);
        if (relations == null || relations.isEmpty()) {
            result.put("list", list);
            result.put("cursor", cursor + size);
            result.put("has_more", false);
            return result;
        }
        List<Long> articleIds = relations.stream().map(TopicRelation::getTargetId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        if (articleIds.isEmpty()) {
            result.put("list", list);
            result.put("cursor", cursor + size);
            result.put("has_more", false);
            return result;
        }
        // 批量查询已发布且未删除的文章
        LambdaQueryWrapper<ApArticle> articleWrapper = new LambdaQueryWrapper<>();
        articleWrapper.in(ApArticle::getId, articleIds)
                      .eq(ApArticle::getStatus, (byte) 9)
                      .eq(ApArticle::getIsDeleted, false);
        List<ApArticle> articles = apArticleMapper.selectList(articleWrapper);
        // 排序：热门按阅读量降序，最新按发布时间降序
        boolean orderByNew = tab != null && tab.endsWith("_new");
        if (orderByNew) {
            articles.sort((a, b) -> {
                Date ta = a.getPublishTime() != null ? a.getPublishTime() : a.getCreatedTime();
                Date tb = b.getPublishTime() != null ? b.getPublishTime() : b.getCreatedTime();
                long lta = ta != null ? ta.getTime() : 0L;
                long ltb = tb != null ? tb.getTime() : 0L;
                return Long.compare(ltb, lta);
            });
        } else {
            articles.sort((a, b) -> Integer.compare(
                    b.getViews() != null ? b.getViews() : 0,
                    a.getViews() != null ? a.getViews() : 0));
        }
        // 内存分页
        int total = articles.size();
        // cursor 是客户端直传的 long：用 long 运算避免 (int) 截断出负数导致 subList 越界
        long rawStart = (cursor / size) * (long) size;
        int start = rawStart > Integer.MAX_VALUE ? total : (int) rawStart;
        if (start >= total) {
            result.put("list", list);
            result.put("cursor", cursor + size);
            result.put("has_more", false);
            return result;
        }
        int end = Math.min(start + size, total);
        boolean hasMore = end < total;
        List<ApArticle> pageArticles = articles.subList(start, end);
        for (ApArticle art : pageArticles) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", art.getId() != null ? String.valueOf(art.getId()) : "");
            item.put("type", "article");
            item.put("title", art.getTitle() != null ? art.getTitle() : "");
            item.put("coverImage", art.getCoverImage() != null ? art.getCoverImage() : "");
            item.put("authorId", art.getAuthorId() != null ? String.valueOf(art.getAuthorId()) : "");
            item.put("authorName", art.getAuthorName() != null ? art.getAuthorName() : "");
            item.put("authorImage", art.getAuthorImage() != null ? art.getAuthorImage() : "");
            item.put("channelName", art.getChannelName() != null ? art.getChannelName() : "");
            item.put("viewCount", art.getViews() != null ? art.getViews() : 0);
            item.put("commentCount", art.getComment() != null ? art.getComment() : 0);
            item.put("createdTime", art.getCreatedTime());
            item.put("publishTime", art.getPublishTime());
            list.add(item);
        }
        result.put("list", list);
        result.put("cursor", cursor + size);
        result.put("has_more", hasMore);
        return result;
    }

    @Override
    public List<TopicRecommendVO> search(String keyword, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<ApTopic> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApTopic::getStatus, 1)
               .like(ApTopic::getName, keyword.trim());
        Page<ApTopic> pageParam = new Page<>(1, limit);
        IPage<ApTopic> pageResult = topicMapper.selectPage(pageParam, wrapper);
        List<ApTopic> topics = pageResult.getRecords();
        return topics.stream().map(t -> {
            TopicRecommendVO vo = new TopicRecommendVO();
            vo.setId(t.getId());
            vo.setName(t.getName());
            vo.setBadge(t.getBadge() != null ? t.getBadge() : "");
            vo.setParticipantCount(t.getParticipantCount() != null ? t.getParticipantCount() : 0L);
            vo.setViewCount(t.getViewCount() != null ? t.getViewCount() : 0L);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> inspirationTopics(int page, int size, String sort, Integer themeType) {
        LambdaQueryWrapper<ApTopic> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApTopic::getStatus, 1);
        if (themeType != null) {
            wrapper.eq(ApTopic::getThemeType, themeType);
        }
        if ("participants".equals(sort)) {
            wrapper.orderByDesc(ApTopic::getParticipantCount);
        } else {
            wrapper.orderByDesc(ApTopic::getViewCount);
        }
        Page<ApTopic> pageParam = new Page<>(page, size);
        IPage<ApTopic> pageResult = topicMapper.selectPage(pageParam, wrapper);
        List<TopicRecommendVO> voList = pageResult.getRecords().stream().map(t -> {
            TopicRecommendVO vo = new TopicRecommendVO();
            vo.setId(t.getId());
            vo.setName(t.getName());
            vo.setBadge(t.getBadge() != null ? t.getBadge() : "");
            vo.setParticipantCount(t.getParticipantCount() != null ? t.getParticipantCount() : 0L);
            vo.setViewCount(t.getViewCount() != null ? t.getViewCount() : 0L);
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("list", voList);
        result.put("total", pageResult.getTotal());
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    @Override
    public List<TopicRecommendVO> recommendedTopics(Long excludeId, int limit) {
        LambdaQueryWrapper<ApTopic> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApTopic::getStatus, 1)
               .ne(excludeId != null, ApTopic::getId, excludeId)
               .orderByDesc(ApTopic::getViewCount);
        Page<ApTopic> pageParam = new Page<>(1, limit);
        IPage<ApTopic> pageResult = topicMapper.selectPage(pageParam, wrapper);
        return pageResult.getRecords().stream().map(t -> {
            TopicRecommendVO vo = new TopicRecommendVO();
            vo.setId(t.getId());
            vo.setName(t.getName());
            vo.setBadge(t.getBadge() != null ? t.getBadge() : "");
            vo.setParticipantCount(t.getParticipantCount() != null ? t.getParticipantCount() : 0L);
            vo.setViewCount(t.getViewCount() != null ? t.getViewCount() : 0L);
            return vo;
        }).collect(Collectors.toList());
    }
}