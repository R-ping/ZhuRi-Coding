package com.heima.content.behavior.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.heima.content.behavior.service.ApReadBehaviorService;
import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.interaction.ApBrowseHistoryMapper;
import com.heima.content.service.article.ApArticleService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.behavior.pojos.ApBrowseHistory;
import com.heima.model.behavior.dtos.ReadBehaviorDto;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.mess.UpdateArticleMess;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class ApReadBehaviorServiceImpl implements ApReadBehaviorService {

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApBrowseHistoryMapper apBrowseHistoryMapper;

    @Autowired
    private ApArticleService apArticleService;

    @Autowired
    private BehaviorEventBus behaviorEventBus;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult readBehavior(ReadBehaviorDto dto) {
        //1.检查参数
        if (dto == null || dto.getArticleId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        //2.仅登录用户计入阅读数；未登录访问不计次（静默成功，不影响浏览）
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.okResult(Map.of("counted", false));
        }
        Integer userId = user.getId();

        //3.同一用户对一篇文章仅累计一次阅读数：以浏览历史记录作为去重依据
        int count = dto.getCount() != null ? dto.getCount() : 1;
        LambdaQueryWrapper<ApBrowseHistory> historyQuery = new LambdaQueryWrapper<>();
        historyQuery.eq(ApBrowseHistory::getUserId, userId.longValue())
                .eq(ApBrowseHistory::getArticleId, dto.getArticleId());
        boolean exists = apBrowseHistoryMapper.selectCount(historyQuery) > 0;

        if (!exists) {
            // 首次浏览该文章：插入浏览历史 + 累加阅读数 + 更新热度分数
            ApBrowseHistory browseHistory = new ApBrowseHistory();
            browseHistory.setUserId(userId.longValue());
            browseHistory.setArticleId(dto.getArticleId());
            browseHistory.setReadCount(count);
            browseHistory.setBrowseTime(new Date());
            apBrowseHistoryMapper.insert(browseHistory);

            LambdaUpdateWrapper<ApArticle> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(ApArticle::getId, dto.getArticleId());
            updateWrapper.setSql("views = views + " + count);
            apArticleMapper.update(null, updateWrapper);

            apArticleService.updateScoreByBehavior(dto.getArticleId(), UpdateArticleMess.UpdateArticleType.VIEWS, count);
        }

        //4.查询文章信息（用于获取作者ID，feeding 等级体系）
        ApArticle article = apArticleMapper.selectById(dto.getArticleId());

        //5.接入等级体系：浏览文章行为 → 读者获得逐日积分(browse_article)，作者获得逐力值(get_read)
        //   BrowseBehaviorHandler 会按天去重，避免重复计分；失败不影响主流程
        if (article != null) {
            try {
                BehaviorContext context = new BehaviorContext(BehaviorType.BROWSE_ARTICLE, userId);
                context.withTarget(1, dto.getArticleId());
                Long authorId = article.getAuthorId();
                if (authorId != null && authorId > 0) {
                    context.withTargetUser(authorId.intValue());
                }
                context.withUserInfo(user.getNickname(), user.getImage());
                behaviorEventBus.execute(context);
            } catch (Exception e) {
                log.error("文章浏览接入等级体系失败: articleId={}, userId={}", dto.getArticleId(), userId, e);
            }
        }

        return ResponseResult.okResult(Map.of("counted", !exists));
    }
}