package com.heima.content.controller.page;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * AI 速读广场页（SSR，兼 SEO 流量入口）
 *
 * <p>聚合"有 AI 摘要元数据"的已发布非水文文章，公开页面；
 * 标题/摘要/作者信息服务端直出（利于收录），点击进入 SSR 详情页的 AI 摘要卡。
 * 依赖：发布预检已把 AI summary 回填 ap_article.summary（Step1 闭环产物二次复用）。
 */
@Slf4j
@Controller
public class AiReadingController {

    @Autowired
    private ApArticleMapper apArticleMapper;

    private static final int PAGE_SIZE = 30;

    @GetMapping("/article/ai-reading")
    public String aiReading(Model model) {
        List<ApArticle> list = apArticleMapper.selectList(new LambdaQueryWrapper<ApArticle>()
            .eq(ApArticle::getStatus, Status.PUBLISHED.getCode())
            .ne(ApArticle::getIsAigc, 1)
            .isNotNull(ApArticle::getSummary)
            .ne(ApArticle::getSummary, "")
            .isNotNull(ApArticle::getTitle)
            .orderByDesc(ApArticle::getPublishTime)
            .last("LIMIT " + PAGE_SIZE));
        log.info("渲染 AI 速读广场页, 文章数={}", list == null ? 0 : list.size());
        model.addAttribute("articles", list == null ? List.of() : list);
        return "ai-reading";
    }
}
