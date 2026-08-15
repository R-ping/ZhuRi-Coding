package com.heima.content.controller.page;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.apis.user.IUserClient;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.service.level.LevelService;
import com.heima.content.utils.MarkdownUtils;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.search.vos.TocItem;
import java.util.List;
import java.util.Map;
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

    @Autowired
    private LevelService levelService;

    @Autowired
    private IUserClient userClient;

    @Autowired
    private ApFollowMapper apFollowMapper;

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
        // 作者ID（用于正文尾部作者卡片：拉取作者信息、跳转作者主页）
        model.addAttribute("authorId", article.getAuthorId() != null ? article.getAuthorId() : 0L);
        model.addAttribute("publishTime", article.getPublishTime());
        model.addAttribute("readCount", article.getViews() != null ? article.getViews() : 0);
        model.addAttribute("readTime", calculateReadTime(content));
        model.addAttribute("likeCount", article.getLikes() != null ? article.getLikes() : 0);
        model.addAttribute("commentCount", article.getComment() != null ? article.getComment() : 0);
        model.addAttribute("collectCount", article.getCollection() != null ? article.getCollection() : 0);
        model.addAttribute("tocList", tocList);
        model.addAttribute("articleContentHtml", contentHtml);

        // 3. 补充作者信息：逐力值等级（创作等级）、职位、公司、文章数、粉丝数
        fillAuthorExtras(article, model);

        log.info("渲染文章详情页, articleId={}", id);
        return "article";
    }

    /**
     * 填充作者扩展信息（逐力值等级/职位/公司/文章数/粉丝数）
     * 逐力值等级即创作等级，用于作者信息区与右侧边栏展示。
     */
    private void fillAuthorExtras(ApArticle article, Model model) {
        Long authorId = article.getAuthorId();
        if (authorId == null || authorId <= 0) {
            model.addAttribute("authorLevel", 1);
            model.addAttribute("authorLevelTitle", "");
            model.addAttribute("authorJobTitle", "");
            model.addAttribute("authorCompany", "");
            model.addAttribute("articleCount", 0);
            model.addAttribute("fansCount", 0);
            return;
        }

        // 逐力值等级（创作等级）
        int powerLevel = 1;
        String powerTitle = "";
        try {
            Map<String, Object> levelInfo = levelService.getUserLevelInfo(authorId);
            Object pl = levelInfo.get("powerLevel");
            if (pl instanceof Number) {
                powerLevel = ((Number) pl).intValue();
            }
            powerTitle = levelInfo.get("powerTitle") != null ? levelInfo.get("powerTitle").toString() : "";
        } catch (Exception e) {
            log.warn("获取作者逐力值等级失败, authorId={}", authorId, e);
        }
        model.addAttribute("authorLevel", powerLevel);
        model.addAttribute("authorLevelTitle", nullSafe(powerTitle));

        // 职位/公司（用户公开信息）
        String position = "";
        String company = "";
        try {
            ResponseResult userResult = userClient.getPublicInfo(authorId);
            if (userResult != null && userResult.getCode() == 200 && userResult.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> userData = (Map<String, Object>) userResult.getData();
                position = userData.get("position") != null ? userData.get("position").toString() : "";
                company = userData.get("company") != null ? userData.get("company").toString() : "";
            }
        } catch (Exception e) {
            log.warn("获取作者公开信息失败, authorId={}", authorId, e);
        }
        model.addAttribute("authorJobTitle", nullSafe(position));
        model.addAttribute("authorCompany", nullSafe(company));

        // 文章数（已发布、未删除）与粉丝数
        long articleCount = apArticleMapper.selectCount(
            new LambdaQueryWrapper<ApArticle>()
                .eq(ApArticle::getAuthorId, authorId)
                .eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode())
                .eq(ApArticle::getIsDeleted, false));
        long fansCount = apFollowMapper.selectCount(
            new LambdaQueryWrapper<ApFollow>().eq(ApFollow::getFollowUserId, authorId.intValue()));
        model.addAttribute("articleCount", articleCount);
        model.addAttribute("fansCount", fansCount);
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