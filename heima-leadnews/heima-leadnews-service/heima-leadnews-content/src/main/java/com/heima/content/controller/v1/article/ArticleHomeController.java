package com.heima.content.controller.v1.article;

import com.heima.common.annotation.RateLimit;
import com.heima.content.service.article.ApArticleRecommendService;
import com.heima.content.service.article.ApArticleService;
import com.heima.common.constants.ArticleConstants;
import com.heima.model.article.dtos.ArticleHomeDto;
import com.heima.model.article.dtos.ArticleRecommendDto;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/article")
public class ArticleHomeController {

    @Autowired
    private ApArticleService apArticleService;

    @Autowired
    private ApArticleRecommendService apArticleRecommendService;

    /**
     * 加载首页
     * @param dto
     * @return
     */
    @PostMapping("/load/")
    public ResponseResult load(@RequestBody ArticleHomeDto dto){
        return apArticleService.load(dto, ArticleConstants.LOADTYPE_LOAD_MORE);
    }

    /**
     * 加载更多
     * @param dto
     * @return
     */
    @PostMapping("/load/more")
    public ResponseResult loadmore(@RequestBody ArticleHomeDto dto){
        return apArticleService.load(dto, ArticleConstants.LOADTYPE_LOAD_MORE);
    }

    /**
     * 加载最新
     * @param dto
     * @return
     */
    @PostMapping("/load/new")
    public ResponseResult loadnew(@RequestBody ArticleHomeDto dto){
        return apArticleService.load(dto, ArticleConstants.LOADTYPE_LOAD_NEW);
    }

    /**
     * 推荐文章（非确定性排序，基于种子随机洗牌）
     */
    @PostMapping("/recommend")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 500, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 30, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult recommend(@RequestBody ArticleRecommendDto dto) {
        return apArticleRecommendService.recommend(dto);
    }

    /**
     * 综合频道文章列表（流量分流入口1）
     * 推荐/最新分栏通过 dto.subTab 区分
     */
    @PostMapping("/recommend_all")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 800, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 40, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult recommendAll(@RequestBody ArticleRecommendDto dto) {
        return apArticleRecommendService.recommendAll(dto);
    }

    /**
     * 关注分栏文章列表（流量分流入口2）
     * 仅返回当前登录用户所关注作者的文章
     */
    @PostMapping("/recommend_follow")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 500, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 30, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult recommendFollow(@RequestBody ArticleRecommendDto dto) {
        return apArticleRecommendService.recommendFollow(dto);
    }

    /**
     * 分类频道文章列表（流量分流入口3）
     * 通过 dto.channel 指定频道ID，推荐/最新分栏通过 dto.subTab 区分
     */
    @PostMapping("/recommend_cate")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 500, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 30, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult recommendCate(@RequestBody ArticleRecommendDto dto) {
        return apArticleRecommendService.recommendCate(dto);
    }
}