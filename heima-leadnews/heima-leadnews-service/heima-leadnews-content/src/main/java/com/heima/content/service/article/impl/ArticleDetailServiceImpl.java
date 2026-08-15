package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.apis.user.IUserClient;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.column.ApColumnMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.interaction.ApCollectionMapper;
import com.heima.content.mapper.tag.TagMapper;
import com.heima.content.service.article.ArticleDetailService;
import com.heima.content.utils.MarkdownUtils;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.article.vos.ArticleColumnVO;
import com.heima.model.article.vos.ArticleDetailVO;
import com.heima.model.article.vos.ArticleRecommendVO;
import com.heima.model.article.vos.TagVO;
import com.heima.model.article.vos.TocItemVO;
import com.heima.model.behavior.pojos.ApBehaviorLikes;
import com.heima.model.behavior.pojos.ApCollection;
import com.heima.model.column.pojos.ApColumn;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.search.vos.TocItem;
import com.heima.model.tag.pojos.ApTag;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ArticleDetailServiceImpl implements ArticleDetailService {

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleContentMapper apArticleContentMapper;

    @Autowired
    private ApBehaviorLikesMapper apBehaviorLikesMapper;

    @Autowired
    private ApCollectionMapper apCollectionMapper;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private ApColumnMapper apColumnMapper;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private IUserClient userClient;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public ResponseResult getArticleDetail(Long id) {
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文章ID不能为空");
        }

        // 1. 查询文章基本信息
        ApArticle article = apArticleMapper.selectById(id);
        if (article == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }

        ApUser currentUser = AppThreadLocalUtil.getUser();
        Long userId = currentUser != null ? currentUser.getId().longValue() : null;

        // 2. 查询文章内容
        LambdaQueryWrapper<ApArticleContent> contentWrapper = new LambdaQueryWrapper<>();
        contentWrapper.eq(ApArticleContent::getArticleId, id);
        ApArticleContent articleContent = apArticleContentMapper.selectOne(contentWrapper);
        String content = articleContent != null ? articleContent.getContent() : "";

        // 3. 通过 Feign 获取作者信息
        String authorName = article.getAuthorName() != null ? article.getAuthorName() : "";
        String authorAvatar = article.getAuthorImage() != null ? article.getAuthorImage() : "";
        try {
            if (article.getAuthorId() != null) {
                ResponseResult userResult = userClient.getBasicInfo(article.getAuthorId());
                if (userResult != null && userResult.getCode() == 200 && userResult.getData() != null) {
                    Map<String, Object> userData = (Map<String, Object>) userResult.getData();
                    if (userData.get("name") != null) {
                        authorName = userData.get("name").toString();
                    }
                    if (userData.get("image") != null) {
                        authorAvatar = userData.get("image").toString();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取用户基本信息失败, authorId={}", article.getAuthorId(), e);
        }

        // 4. 查询当前用户交互状态
        boolean isDigg = false;
        boolean isCollect = false;
        boolean isFollow = false;

        if (userId != null) {
            // 查询是否点赞
            LambdaQueryWrapper<ApBehaviorLikes> likesWrapper = new LambdaQueryWrapper<>();
            likesWrapper.eq(ApBehaviorLikes::getUserId, userId.intValue())
                    .eq(ApBehaviorLikes::getEntryId, id)
                    .eq(ApBehaviorLikes::getType, 0) // type=0 表示文章
                    .eq(ApBehaviorLikes::getOperation, 0); // operation=0 表示点赞
            isDigg = apBehaviorLikesMapper.selectCount(likesWrapper) > 0;

            // 查询是否收藏
            LambdaQueryWrapper<ApCollection> collectWrapper = new LambdaQueryWrapper<>();
            collectWrapper.eq(ApCollection::getUserId, userId.intValue())
                    .eq(ApCollection::getArticleId, id);
            isCollect = apCollectionMapper.selectCount(collectWrapper) > 0;

            // 查询是否关注作者
            if (article.getAuthorId() != null) {
                LambdaQueryWrapper<ApFollow> followWrapper = new LambdaQueryWrapper<>();
                followWrapper.eq(ApFollow::getUserId, userId.intValue())
                        .eq(ApFollow::getFollowUserId, article.getAuthorId().intValue());
                isFollow = apFollowMapper.selectCount(followWrapper) > 0;
            }
        }

        // 5. 解析标签
        List<TagVO> tagVOs = parseTags(article.getTags());

        // 6. 计算阅读时间
        String readTime = calculateReadTime(content);

        // 7. 生成目录
        List<TocItemVO> tocList = generateToc(content);

        // 8. 提取简介
        String briefContent = extractBriefContent(content);

        // 9. 构建 VO
        ArticleDetailVO vo = new ArticleDetailVO();
        vo.setArticleId(article.getId() != null ? article.getId().toString() : "");
        vo.setTitle(article.getTitle() != null ? article.getTitle() : "");
        vo.setBriefContent(briefContent);
        vo.setCoverImage(article.getCoverImage() != null ? article.getCoverImage() : "");
        vo.setViewCount(article.getViews() != null ? article.getViews() : 0);
        vo.setCollectCount(article.getCollection() != null ? article.getCollection() : 0);
        vo.setDiggCount(article.getLikes() != null ? article.getLikes() : 0);
        vo.setCommentCount(article.getComment() != null ? article.getComment() : 0);
        vo.setReadTime(readTime);
        vo.setStatus(article.getStatus() != null ? article.getStatus().intValue() : 0);
        vo.setIsOriginal(article.getOrigin() != null && article.getOrigin() ? 1 : 0);
        vo.setAuthorId(article.getAuthorId() != null ? article.getAuthorId().toString() : "");
        vo.setAuthorName(authorName);
        vo.setAuthorAvatar(authorAvatar);
        vo.setAuthorCompany("");
        vo.setAuthorJobTitle("");
        vo.setAuthorLevel(0);
        vo.setFollowerCount(0);
        vo.setPostArticleCount(0);
        vo.setCategoryId(article.getChannelId() != null ? article.getChannelId().toString() : "");
        vo.setCategoryName(article.getChannelName() != null ? article.getChannelName() : "");
        vo.setTags(tagVOs != null ? tagVOs : new ArrayList<>());
        vo.setIsDigg(isDigg);
        vo.setIsFollow(isFollow);
        vo.setIsCollect(isCollect);
        vo.setArticleContent(content);
        vo.setPublishTime(article.getPublishTime() != null ? DATE_FORMAT.format(article.getPublishTime()) : "");
        vo.setTocList(tocList != null ? tocList : new ArrayList<>());

        return ResponseResult.okResult(vo);
    }

    @Override
    public ResponseResult getArticleColumn(Long id) {
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文章ID不能为空");
        }

        ApArticle article = apArticleMapper.selectById(id);
        if (article == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }

        // 无专栏时返回空对象
        if (article.getColumnId() == null) {
            return ResponseResult.okResult(new ArticleColumnVO());
        }

        // 查询专栏信息
        ApColumn column = apColumnMapper.selectById(article.getColumnId());
        if (column == null) {
            return ResponseResult.okResult(new ArticleColumnVO());
        }

        // 查询该专栏下文章的前一篇和后一篇（按 publish_time 排序）
        Long prevArticleId = null;
        String prevArticleTitle = null;
        Long nextArticleId = null;
        String nextArticleTitle = null;

        // 前一篇：publish_time < 当前文章publish_time，取最大的一条
        LambdaQueryWrapper<ApArticle> prevWrapper = new LambdaQueryWrapper<>();
        prevWrapper.eq(ApArticle::getColumnId, article.getColumnId())
                .eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode())
                .lt(ApArticle::getPublishTime, article.getPublishTime())
                .orderByDesc(ApArticle::getPublishTime)
                .last("LIMIT 1");
        List<ApArticle> prevArticles = apArticleMapper.selectList(prevWrapper);
        if (!prevArticles.isEmpty()) {
            prevArticleId = prevArticles.get(0).getId();
            prevArticleTitle = prevArticles.get(0).getTitle();
        }

        // 后一篇：publish_time > 当前文章publish_time，取最小的一条
        LambdaQueryWrapper<ApArticle> nextWrapper = new LambdaQueryWrapper<>();
        nextWrapper.eq(ApArticle::getColumnId, article.getColumnId())
                .eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode())
                .gt(ApArticle::getPublishTime, article.getPublishTime())
                .orderByAsc(ApArticle::getPublishTime)
                .last("LIMIT 1");
        List<ApArticle> nextArticles = apArticleMapper.selectList(nextWrapper);
        if (!nextArticles.isEmpty()) {
            nextArticleId = nextArticles.get(0).getId();
            nextArticleTitle = nextArticles.get(0).getTitle();
        }

        // 查询当前用户是否关注专栏
        boolean isFollow = false;
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser != null && column.getAuthorId() != null) {
            LambdaQueryWrapper<ApFollow> followWrapper = new LambdaQueryWrapper<>();
            followWrapper.eq(ApFollow::getUserId, currentUser.getId().intValue())
                    .eq(ApFollow::getFollowUserId, column.getAuthorId().intValue());
            isFollow = apFollowMapper.selectCount(followWrapper) > 0;
        }

        ArticleColumnVO vo = new ArticleColumnVO();
        vo.setColumnId(column.getId());
        vo.setColumnTitle(column.getTitle() != null ? column.getTitle() : "");
        vo.setColumnCover(column.getCoverImage() != null ? column.getCoverImage() : "");
        vo.setColumnDescription(column.getDescription() != null ? column.getDescription() : "");
        vo.setFollowCnt(column.getSubscribeCount() != null ? column.getSubscribeCount() : 0);
        vo.setArticleCnt(column.getArticleCount() != null ? column.getArticleCount() : 0);
        vo.setIsFollow(isFollow);
        vo.setPrevArticleId(prevArticleId != null ? prevArticleId.toString() : null);
        vo.setPrevArticleTitle(prevArticleTitle);
        vo.setNextArticleId(nextArticleId != null ? nextArticleId.toString() : null);
        vo.setNextArticleTitle(nextArticleTitle);

        return ResponseResult.okResult(vo);
    }

    @Override
    public ResponseResult getRelatedArticles(Long id, Long cursor, Integer size) {
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文章ID不能为空");
        }
        if (size == null || size <= 0) {
            size = 5;
        }

        ApArticle currentArticle = apArticleMapper.selectById(id);
        if (currentArticle == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }

        // 相关推荐策略：优先推作者的其他文章（最多3篇），不足时依次用同频道文章、
        // 全局文章兜底补齐到 size 篇。既突出作者作品，又保证侧边栏始终有足够内容可浏览。
        List<ApArticle> articles = new ArrayList<>();
        Set<Long> includedIds = new HashSet<>();
        includedIds.add(id);

        int authorLimit = Math.min(3, size);
        if (currentArticle.getAuthorId() != null && authorLimit > 0) {
            LambdaQueryWrapper<ApArticle> authorWrapper = new LambdaQueryWrapper<>();
            authorWrapper.eq(ApArticle::getAuthorId, currentArticle.getAuthorId())
                    .eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode())
                    .eq(ApArticle::getIsDeleted, false)
                    .ne(ApArticle::getId, id);
            if (cursor != null && cursor > 0) {
                authorWrapper.lt(ApArticle::getId, cursor);
            }
            authorWrapper.orderByDesc(ApArticle::getPublishTime)
                    .orderByDesc(ApArticle::getId)
                    .last("LIMIT " + authorLimit);
            List<ApArticle> authorArticles = apArticleMapper.selectList(authorWrapper);
            for (ApArticle a : authorArticles) {
                includedIds.add(a.getId());
                articles.add(a);
            }
        }

        // 作者文章不足时，用同频道文章补齐
        int channelLimit = size - articles.size();
        if (channelLimit > 0) {
            LambdaQueryWrapper<ApArticle> channelWrapper = new LambdaQueryWrapper<>();
            channelWrapper.eq(ApArticle::getChannelId, currentArticle.getChannelId())
                    .eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode())
                    .eq(ApArticle::getIsDeleted, false)
                    .ne(ApArticle::getId, id);
            if (cursor != null && cursor > 0) {
                channelWrapper.lt(ApArticle::getId, cursor);
            }
            channelWrapper.notIn(ApArticle::getId, includedIds);
            channelWrapper.orderByDesc(ApArticle::getPublishTime)
                    .orderByDesc(ApArticle::getId)
                    .last("LIMIT " + channelLimit);
            List<ApArticle> channelArticles = apArticleMapper.selectList(channelWrapper);
            for (ApArticle a : channelArticles) {
                includedIds.add(a.getId());
                articles.add(a);
            }
        }

        // 同频道仍不足时，全局兜底（排除已加入的文章，保证侧边栏始终有足够内容）
        int globalLimit = size - articles.size();
        if (globalLimit > 0) {
            LambdaQueryWrapper<ApArticle> globalWrapper = new LambdaQueryWrapper<>();
            globalWrapper.eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode())
                    .eq(ApArticle::getIsDeleted, false)
                    .ne(ApArticle::getId, id)
                    .notIn(ApArticle::getId, includedIds)
                    .orderByDesc(ApArticle::getPublishTime)
                    .orderByDesc(ApArticle::getId)
                    .last("LIMIT " + globalLimit);
            List<ApArticle> globalArticles = apArticleMapper.selectList(globalWrapper);
            articles.addAll(globalArticles);
        }

        List<ArticleRecommendVO> list = buildRecommendVOList(articles);

        long newCursor = articles.isEmpty() ? (cursor != null ? cursor : 0) : articles.get(articles.size() - 1).getId();
        boolean hasMore = articles.size() >= size;

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("cursor", newCursor);
        result.put("has_more", hasMore);

        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult getFeaturedArticles(Long id, Long cursor, Integer size) {
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文章ID不能为空");
        }
        if (size == null || size <= 0) {
            size = 5;
        }

        ApArticle currentArticle = apArticleMapper.selectById(id);
        if (currentArticle == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }

        // 查询有相同标签的文章
        List<String> tags = currentArticle.getTags();
        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode())
                .ne(ApArticle::getId, id);

        // 使用 JSON_OVERLAPS 查询相同标签
        if (tags != null && !tags.isEmpty()) {
            String tagsJson = com.alibaba.fastjson.JSON.toJSONString(tags);
            wrapper.apply("JSON_OVERLAPS(tags, {0})", tagsJson);
        }

        if (cursor != null && cursor > 0) {
            wrapper.lt(ApArticle::getId, cursor);
        }

        wrapper.orderByDesc(ApArticle::getPublishTime)
                .orderByDesc(ApArticle::getId)
                .last("LIMIT " + size);

        List<ApArticle> articles = apArticleMapper.selectList(wrapper);
        List<ArticleRecommendVO> list = buildRecommendVOList(articles);

        long newCursor = articles.isEmpty() ? (cursor != null ? cursor : 0) : articles.get(articles.size() - 1).getId();
        boolean hasMore = articles.size() >= size;

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("cursor", newCursor);
        result.put("has_more", hasMore);

        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult getRecommendArticles(Long id, Long cursor, Integer size) {
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文章ID不能为空");
        }
        if (size == null || size <= 0) {
            size = 5;
        }

        // 查询已发布且 is_recommend=1 的文章，按 score 降序
        List<ApArticle> articles = apArticleMapper.selectRecommendArticles(id, cursor, size);
        List<ArticleRecommendVO> list = buildRecommendVOList(articles);

        long newCursor = articles.isEmpty() ? (cursor != null ? cursor : 0) : articles.get(articles.size() - 1).getId();
        boolean hasMore = articles.size() >= size;

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("cursor", newCursor);
        result.put("has_more", hasMore);

        return ResponseResult.okResult(result);
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析标签列表
     */
    private List<TagVO> parseTags(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return new ArrayList<>();
        }

        List<TagVO> result = new ArrayList<>();
        // 从 ap_tag 表查询标签信息
        for (String tagName : tagNames) {
            if (StringUtils.isBlank(tagName)) {
                continue;
            }
            LambdaQueryWrapper<ApTag> tagWrapper = new LambdaQueryWrapper<>();
            tagWrapper.eq(ApTag::getName, tagName);
            ApTag tag = tagMapper.selectOne(tagWrapper);
            TagVO tagVO = new TagVO();
            if (tag != null) {
                tagVO.setTagId(tag.getId().toString());
                tagVO.setTagName(tag.getName());
                tagVO.setColor("");
            } else {
                tagVO.setTagId("");
                tagVO.setTagName(tagName);
                tagVO.setColor("");
            }
            result.add(tagVO);
        }
        return result;
    }

    /**
     * 计算阅读时间（分钟）
     */
    private String calculateReadTime(String content) {
        if (StringUtils.isBlank(content)) {
            return "1";
        }
        // 去除 Markdown 标记和 HTML 标签，计算纯文本长度
        String plainText = content.replaceAll("#+\\s*", "")
                .replaceAll("!\\[.*?\\]\\(.*?\\)", "")
                .replaceAll("\\[.*?\\]\\(.*?\\)", "")
                .replaceAll("```[\\s\\S]*?```", "")
                .replaceAll("`[^`]*`", "")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\s+", "");
        int charCount = plainText.length();
        // 按每分钟阅读 500 字计算
        int minutes = Math.max(1, (int) Math.ceil(charCount / 500.0));
        return String.valueOf(minutes);
    }

    /**
     * 从文章内容生成目录
     */
    private List<TocItemVO> generateToc(String content) {
        if (StringUtils.isBlank(content)) {
            return new ArrayList<>();
        }
        try {
            // 先转为 HTML 再提取目录
            String html = MarkdownUtils.toHtml(content);
            List<TocItem> tocItems = MarkdownUtils.extractToc(html);
            return tocItems.stream().map(item -> {
                TocItemVO vo = new TocItemVO();
                vo.setId(item.getId());
                vo.setText(item.getText());
                vo.setLevel(item.getLevel());
                return vo;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("生成目录失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 提取文章简介（前200字符）
     */
    private String extractBriefContent(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        String plainText = content.replaceAll("#+\\s*", "")
                .replaceAll("!\\[.*?\\]\\(.*?\\)", "")
                .replaceAll("\\[.*?\\]\\(.*?\\)", "")
                .replaceAll("```[\\s\\S]*?```", "")
                .replaceAll("`[^`]*`", "")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (plainText.length() > 200) {
            return plainText.substring(0, 200) + "...";
        }
        return plainText;
    }

    /**
     * 构建推荐 VO 列表
     */
    private List<ArticleRecommendVO> buildRecommendVOList(List<ApArticle> articles) {
        if (articles == null || articles.isEmpty()) {
            return new ArrayList<>();
        }
        return articles.stream().map(article -> {
            ArticleRecommendVO vo = new ArticleRecommendVO();
            vo.setArticleId(article.getId() != null ? article.getId().toString() : "");
            vo.setTitle(article.getTitle() != null ? article.getTitle() : "");
            vo.setBriefContent(extractBriefContent(
                    Optional.ofNullable(apArticleContentMapper.selectOne(
                            new LambdaQueryWrapper<ApArticleContent>()
                                    .eq(ApArticleContent::getArticleId, article.getId())))
                            .map(ApArticleContent::getContent).orElse("")));
            vo.setCoverImage(article.getCoverImage() != null ? article.getCoverImage() : "");
            vo.setAuthorName(article.getAuthorName() != null ? article.getAuthorName() : "");
            vo.setAuthorAvatar(article.getAuthorImage() != null ? article.getAuthorImage() : "");
            vo.setPublishTime(article.getPublishTime() != null ? DATE_FORMAT.format(article.getPublishTime()) : "");
            vo.setViewCount(article.getViews() != null ? article.getViews() : 0);
            vo.setCollectCount(article.getCollection() != null ? article.getCollection() : 0);
            vo.setDiggCount(article.getLikes() != null ? article.getLikes() : 0);
            vo.setCommentCount(article.getComment() != null ? article.getComment() : 0);
            vo.setReadTime(calculateReadTime(
                    Optional.ofNullable(apArticleContentMapper.selectOne(
                            new LambdaQueryWrapper<ApArticleContent>()
                                    .eq(ApArticleContent::getArticleId, article.getId())))
                            .map(ApArticleContent::getContent).orElse("")));
            vo.setCategoryName(article.getChannelName() != null ? article.getChannelName() : "");
            vo.setTags(parseTags(article.getTags()));
            return vo;
        }).collect(Collectors.toList());
    }
}