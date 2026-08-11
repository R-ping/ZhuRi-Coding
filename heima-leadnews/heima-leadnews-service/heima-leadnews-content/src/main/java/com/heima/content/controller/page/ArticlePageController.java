package com.heima.content.controller.page;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.utils.MarkdownUtils;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.search.vos.TocItem;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 文章详情页 MVC 控制器（方案②：FTL 服务端渲染）
 *
 * <p>访问地址：/content/article/{id}（经网关 /content 前缀转发，StripPrefix 后为 /article/{id}）
 * 由网关白名单放行，无需登录即可浏览文章正文，利于 SEO。
 *
 * <p>正文 Markdown 在服务端通过 MarkdownUtils 渲染为 HTML 后直接注入模板，
 * 目录（TOC）同样在服务端提取，实现真正的服务端渲染。
 */
@Controller
@RequestMapping("/article")
@Slf4j
public class ArticlePageController {

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleContentMapper apArticleContentMapper;

    /**
     * 渲染文章详情页
     * GET /content/article/{id}
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable("id") Long id, Model model) {
        if (id == null) {
            return "error/404";
        }
        ApArticle article = apArticleMapper.selectById(id);
        if (article == null || article.isDeletedArticle()) {
            return "error/404";
        }

        // 1. 读取正文内容并服务端渲染为 HTML
        String content = "";
        ApArticleContent articleContent = apArticleContentMapper.selectOne(
            new LambdaQueryWrapper<ApArticleContent>()
                .eq(ApArticleContent::getArticleId, id));
        if (articleContent != null) {
            content = MarkdownUtils.normalizeContent(articleContent.getContent());
        }
        String rawHtml = MarkdownUtils.toHtml(content);
        String contentHtml = MarkdownUtils.injectHeadingAnchors(rawHtml);
        List<TocItem> tocList = MarkdownUtils.extractToc(rawHtml);

        // 2. 填充模板数据
        model.addAttribute("articleId", id);
        model.addAttribute("title", nullSafe(article.getTitle()));
        model.addAttribute("authorName", nullSafe(article.getAuthorName()));
        model.addAttribute("authorAvatar", nullSafe(article.getAuthorImage()));
        model.addAttribute("publishTime", article.getPublishTime());
        model.addAttribute("readCount", article.getViews() != null ? article.getViews() : 0);
        model.addAttribute("readTime", calculateReadTime(content));
        model.addAttribute("likeCount", article.getLikes() != null ? article.getLikes() : 0);
        model.addAttribute("commentCount", article.getComment() != null ? article.getComment() : 0);
        model.addAttribute("collectCount", article.getCollection() != null ? article.getCollection() : 0);
        model.addAttribute("tocList", tocList);
        model.addAttribute("articleContentHtml", contentHtml);

        log.info("渲染文章详情页, articleId={}", id);
        return "article";
    }

    /**
     * 计算阅读时间（分钟）：按每分钟阅读 500 字估算
     */
    private String calculateReadTime(String content) {
        if (StringUtils.isBlank(content)) {
            return "1";
        }
        String plainText = content.replaceAll("#+\\s*", "")
            .replaceAll("!\\[.*?\\]\\(.*?\\)", "")
            .replaceAll("\\[.*?\\]\\(.*?\\)", "")
            .replaceAll("```[\\s\\S]*?```", "")
            .replaceAll("`[^`]*`", "")
            .replaceAll("<[^>]+>", "")
            .replaceAll("\\s+", "");
        int minutes = Math.max(1, (int) Math.ceil(plainText.length() / 500.0));
        return String.valueOf(minutes);
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}