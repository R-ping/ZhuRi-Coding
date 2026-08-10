package com.heima.content.controller.v1.article;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.interaction.ApCollectionMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.pojos.ApBehaviorLikes;
import com.heima.model.behavior.pojos.ApCollection;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 文章详情页互动接口
 * 提供点赞、收藏、关注等切换式互动操作，直接操作 Mapper 简化实现
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/article")
public class ArticleInteractionController {

    @Autowired
    private ApBehaviorLikesMapper apBehaviorLikesMapper;

    @Autowired
    private ApCollectionMapper apCollectionMapper;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    /**
     * 点赞/取消点赞文章（切换式）
     * POST /api/v1/article/{id}/like
     *
     * @param id 文章ID
     * @return { "liked": true/false, "diggCount": number }
     */
    @PostMapping("/{id}/like")
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult like(@PathVariable Long id) {
        // 检查登录
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        // 查询文章是否存在
        ApArticle article = apArticleMapper.selectById(id);
        if (article == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }

        // 查询是否已点赞（type=0 表示文章，operation=0 表示点赞）
        LambdaQueryWrapper<ApBehaviorLikes> query = new LambdaQueryWrapper<>();
        query.eq(ApBehaviorLikes::getEntryId, id);
        query.eq(ApBehaviorLikes::getUserId, user.getId());
        query.eq(ApBehaviorLikes::getType, 0);
        query.eq(ApBehaviorLikes::getOperation, 0);
        ApBehaviorLikes existing = apBehaviorLikesMapper.selectOne(query);

        boolean liked;
        if (existing != null) {
            // 已点赞 → 取消点赞（operation=1 表示取消）
            existing.setOperation(1);
            apBehaviorLikesMapper.updateById(existing);
            // 更新文章点赞数减1
            apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                    .eq(ApArticle::getId, id)
                    .setSql("likes = GREATEST(likes - 1, 0)"));
            liked = false;
            log.info("用户{}取消点赞文章{}", user.getId(), id);
        } else {
            // 未点赞 → 新增点赞记录
            ApBehaviorLikes like = new ApBehaviorLikes();
            like.setEntryId(id);
            like.setUserId(user.getId());
            like.setType(0); // 文章
            like.setOperation(0); // 点赞
            like.setCreatedTime(new Date());
            apBehaviorLikesMapper.insert(like);
            // 更新文章点赞数加1
            apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                    .eq(ApArticle::getId, id)
                    .setSql("likes = likes + 1"));
            liked = true;
            log.info("用户{}点赞文章{}", user.getId(), id);
        }

        // 重新查询最新点赞数
        ApArticle updatedArticle = apArticleMapper.selectById(id);
        int diggCount = updatedArticle.getLikes() != null ? updatedArticle.getLikes() : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("liked", liked);
        result.put("diggCount", diggCount);
        return ResponseResult.okResult(result);
    }

    /**
     * 收藏/取消收藏文章（切换式）
     * POST /api/v1/article/{id}/collect
     *
     * @param id 文章ID
     * @return { "collected": true/false, "collectCount": number }
     */
    @PostMapping("/{id}/collect")
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult collect(@PathVariable Long id) {
        // 检查登录
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        // 查询文章是否存在
        ApArticle article = apArticleMapper.selectById(id);
        if (article == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }

        // 查询是否已收藏
        LambdaQueryWrapper<ApCollection> query = new LambdaQueryWrapper<>();
        query.eq(ApCollection::getUserId, user.getId());
        query.eq(ApCollection::getArticleId, id);
        ApCollection existing = apCollectionMapper.selectOne(query);

        boolean collected;
        if (existing != null) {
            // 已收藏 → 删除收藏记录
            apCollectionMapper.deleteById(existing.getId());
            // 更新文章收藏数减1
            apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                    .eq(ApArticle::getId, id)
                    .setSql("collection = GREATEST(collection - 1, 0)"));
            collected = false;
            log.info("用户{}取消收藏文章{}", user.getId(), id);
        } else {
            // 未收藏 → 新增收藏记录
            ApCollection collection = new ApCollection();
            collection.setUserId(user.getId());
            collection.setArticleId(id);
            collection.setCreatedTime(new Date());
            apCollectionMapper.insert(collection);
            // 更新文章收藏数加1
            apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                    .eq(ApArticle::getId, id)
                    .setSql("collection = collection + 1"));
            collected = true;
            log.info("用户{}收藏文章{}", user.getId(), id);
        }

        // 重新查询最新收藏数
        ApArticle updatedArticle = apArticleMapper.selectById(id);
        int collectCount = updatedArticle.getCollection() != null ? updatedArticle.getCollection() : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("collected", collected);
        result.put("collectCount", collectCount);
        return ResponseResult.okResult(result);
    }

    /**
     * 关注/取消关注作者（切换式）
     * POST /api/v1/article/{id}/follow
     *
     * @param id 文章ID
     * @return { "followed": true/false }
     */
    @PostMapping("/{id}/follow")
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult follow(@PathVariable Long id) {
        // 检查登录
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        // 查询文章获取作者ID
        ApArticle article = apArticleMapper.selectById(id);
        if (article == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }

        Long authorIdLong = article.getAuthorId();
        if (authorIdLong == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章作者不存在");
        }
        Integer authorId = authorIdLong.intValue();

        // 不能关注自己
        if (user.getId().equals(authorId)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不能关注自己");
        }

        // 查询是否已关注
        LambdaQueryWrapper<ApFollow> query = new LambdaQueryWrapper<>();
        query.eq(ApFollow::getUserId, user.getId());
        query.eq(ApFollow::getFollowUserId, authorId);
        ApFollow existing = apFollowMapper.selectOne(query);

        boolean followed;
        if (existing != null) {
            // 已关注 → 删除关注记录
            apFollowMapper.deleteById(existing.getId());
            followed = false;
            log.info("用户{}取消关注作者{}", user.getId(), authorId);
        } else {
            // 未关注 → 新增关注记录
            ApFollow follow = new ApFollow();
            follow.setUserId(user.getId());
            follow.setFollowUserId(authorId);
            follow.setCreatedTime(new Date());
            apFollowMapper.insert(follow);
            followed = true;
            log.info("用户{}关注作者{}", user.getId(), authorId);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("followed", followed);
        return ResponseResult.okResult(result);
    }
}