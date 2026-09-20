package com.zhuri.coding.content.controller.v1.article;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhuri.coding.content.constants.LevelScoreActionCode;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.interaction.ApArticleReportMapper;
import com.zhuri.coding.content.mapper.interaction.ApBehaviorLikesMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.dtos.ArticleReportDto;
import com.zhuri.coding.model.behavior.pojos.ApArticleReport;
import com.zhuri.coding.model.behavior.pojos.ApBehaviorLikes;
import com.zhuri.coding.model.behavior.pojos.ApCollection;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
    private ApArticleReportMapper apArticleReportMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private LevelService levelService;

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

        // 查询该用户对这篇文章的全部点赞记录（不看 operation，用于切换点赞/取消状态并复用同一行）
        LambdaQueryWrapper<ApBehaviorLikes> query = new LambdaQueryWrapper<>();
        query.eq(ApBehaviorLikes::getEntryId, id);
        query.eq(ApBehaviorLikes::getUserId, user.getId());
        query.eq(ApBehaviorLikes::getType, 0);
        ApBehaviorLikes record = apBehaviorLikesMapper.selectOne(query);

        boolean liked;
        Integer currentOperation = record == null ? null : record.getOperation();
        if (record != null && currentOperation != null && currentOperation == 0) {
            // 已点赞 → 取消点赞（operation=1 表示取消），并回退本次点赞获得的逐日进度/积分
            record.setOperation(1);
            apBehaviorLikesMapper.updateById(record);
            // 更新文章点赞数减1
            apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                    .eq(ApArticle::getId, id)
                    .setSql("likes = GREATEST(likes - 1, 0)"));
            liked = false;
            log.info("用户{}取消点赞文章{}", user.getId(), id);
            try {
                levelService.rollbackActionWithLimit(user.getId().longValue(), LevelScoreActionCode.LIKE_ARTICLE, "取消点赞文章ID:" + id);
            } catch (Exception e) {
                log.warn("取消点赞回退逐日等级行为失败: userId={}, articleId={}", user.getId(), id, e);
            }
        } else {
            // 未点赞 → 置为已点赞：有旧记录则复用（operation 1→0），无记录则新增；点赞数加1
            if (record != null) {
                record.setOperation(0);
                apBehaviorLikesMapper.updateById(record);
            } else {
                ApBehaviorLikes like = new ApBehaviorLikes();
                like.setEntryId(id);
                like.setUserId(user.getId());
                like.setType(0); // 文章
                like.setOperation(0); // 点赞
                like.setCreatedTime(new Date());
                apBehaviorLikesMapper.insert(like);
            }
            apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                    .eq(ApArticle::getId, id)
                    .setSql("likes = likes + 1"));
            liked = true;
            log.info("用户{}点赞文章{}", user.getId(), id);
            // 每次点赞都累计逐日等级"点赞"行为（like_article）：受 like_article 每日上限控制，
            // 取消点赞时通过 rollbackActionWithLimit 回退本次累计，避免反复取消/点赞刷分
            try {
                levelService.recordActionWithLimit(user.getId().longValue(), LevelScoreActionCode.LIKE_ARTICLE, "点赞文章ID:" + id);
            } catch (Exception e) {
                log.warn("点赞记录逐日等级行为失败: userId={}, articleId={}", user.getId(), id, e);
            }
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
            // 已收藏 → 删除收藏记录，并回退本次收藏获得的逐日进度/积分
            apCollectionMapper.deleteById(existing.getId());
            // 更新文章收藏数减1
            apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                    .eq(ApArticle::getId, id)
                    .setSql("collection = GREATEST(collection - 1, 0)"));
            collected = false;
            log.info("用户{}取消收藏文章{}", user.getId(), id);
            try {
                levelService.rollbackActionWithLimit(user.getId().longValue(), LevelScoreActionCode.COLLECT_ARTICLE, "取消收藏文章ID:" + id);
            } catch (Exception e) {
                log.error("取消收藏回退逐日等级行为失败: action=collect_article, userId={}, articleId={}", user.getId(), id, e);
            }
        } else {
            // 未收藏 → 新增收藏记录；唯一索引 uk_collection_user_article 保证同用户同文章仅一条
            boolean newlyInserted;
            try {
                ApCollection collection = new ApCollection();
                collection.setUserId(user.getId());
                collection.setArticleId(id);
                collection.setCreatedTime(new Date());
                apCollectionMapper.insert(collection);
                newlyInserted = true;
            } catch (DuplicateKeyException e) {
                // 并发下另一请求已插入收藏记录，幂等视为已收藏，且不再重复累加计数
                newlyInserted = false;
                log.info("并发收藏冲突，视为已收藏, userId={}, articleId={}", user.getId(), id);
            }
            if (newlyInserted) {
                // 更新文章收藏数加1
                apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                        .eq(ApArticle::getId, id)
                        .setSql("collection = collection + 1"));
                // 记录逐日等级"收藏"行为（collect_article）：累计今日进度 + 逐日分，失败不影响收藏主流程
                // （与 /behavior/collect 行为总线口径一致；受 collect_article 每日上限控制）
                try {
                    levelService.recordActionWithLimit(user.getId().longValue(), LevelScoreActionCode.COLLECT_ARTICLE, "收藏文章ID:" + id);
                } catch (Exception e) {
                    log.error("记录逐日等级行为失败: action=collect_article, userId={}", user.getId(), e);
                }
            }
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
     * 举报文章
     * POST /api/v1/article/{id}/report
     *
     * @param id  文章ID
     * @param dto 举报参数（reason 必填，description/imageUrls 选填）
     * @return { "reportId": number }
     */
    @PostMapping("/{id}/report")
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult report(@PathVariable Long id, @RequestBody ArticleReportDto dto) {
        // 检查登录
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        // 查询文章是否存在（并取作者ID）
        ApArticle article = apArticleMapper.selectById(id);
        if (article == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }

        // 参数校验：举报原因必填
        String reason = dto.getReason() == null ? "" : dto.getReason().trim();
        if (reason.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请选择举报原因");
        }
        if (reason.length() > 100) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "举报原因过长");
        }

        // 参数校验：补充说明 ≤100 字
        String description = dto.getDescription() == null ? "" : dto.getDescription().trim();
        if (description.length() > 100) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "补充说明不能超过100字");
        }

        // 参数校验：举报图片最多 4 张
        java.util.List<String> imageUrls = dto.getImageUrls() == null ? new java.util.ArrayList<>() : dto.getImageUrls();
        if (imageUrls.size() > 4) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "举报图片最多上传4张");
        }

        // 组装举报记录并落库
        ApArticleReport report = new ApArticleReport();
        report.setUserId(user.getId());
        report.setArticleId(id);
        report.setAuthorId(article.getAuthorId());
        report.setReason(reason);
        report.setDescription(description);
        report.setImageUrls(String.join(",", imageUrls));
        report.setStatus(0); // 待处理
        report.setCreatedTime(new Date());
        apArticleReportMapper.insert(report);
        log.info("用户{}举报文章{}，原因：{}", user.getId(), id, reason);

        Map<String, Object> result = new HashMap<>();
        result.put("reportId", report.getId());
        return ResponseResult.okResult(result);
    }
}