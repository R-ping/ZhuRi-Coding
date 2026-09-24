package com.zhuri.coding.content.controller.page;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.content.service.comment.ApCommentService;
import com.zhuri.coding.content.utils.MarkdownUtils;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.follow.pojos.ApFollow;
import com.zhuri.coding.model.search.vos.TocItem;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import jakarta.servlet.http.HttpServletResponse;
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
    private ApCommentService apCommentService;

    @Autowired
    private IUserClient userClient;

    @Autowired
    private ApFollowMapper apFollowMapper;

    /** 站点对外域名（canonical / OG 绝对 URL 前缀；空时在模板输出相对路径便于本地调试） */
    @Value("${app.seo.base-url:}")
    private String seoBaseUrl;

    /**
     * 渲染文章详情页
     * GET /content/article/{id}
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable("id") Long id, Model model, HttpServletResponse response) {
        if (id == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        ApArticle article = apArticleMapper.selectById(id);
        if (article == null || article.isDeletedArticle()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
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
        model.addAttribute("isAigc", article.getIsAigc() != null ? article.getIsAigc() : 0);
        model.addAttribute("authorName", nullSafe(article.getAuthorName()));
        // 作者头像：为空时回退到占位头像，避免 <img src=""> 显示裂图
        model.addAttribute("authorAvatar", defaultAvatar(article.getAuthorImage()));
        // 作者ID（用于正文尾部作者卡片：拉取作者信息、跳转作者主页）
        model.addAttribute("authorId", article.getAuthorId() != null ? article.getAuthorId() : 0L);
        model.addAttribute("publishTime", article.getPublishTime());
        model.addAttribute("readCount", article.getViews() != null ? article.getViews() : 0);
        // 正文纯文本只清洗一次：阅读时长与 meta description 共用，避免同一请求内重复执行全文正则
        String plainContent = plainText(content);
        model.addAttribute("readTime", calculateReadTime(plainContent));
        model.addAttribute("likeCount", article.getLikes() != null ? article.getLikes() : 0);
        model.addAttribute("commentCount", apCommentService.countTopComments(id));
        model.addAttribute("collectCount", article.getCollection() != null ? article.getCollection() : 0);
        model.addAttribute("tocList", tocList);
        model.addAttribute("articleContentHtml", contentHtml);

        // 2.1 SEO 元数据（meta description / canonical / OG / JSON-LD 结构化数据）
        model.addAttribute("seoBaseUrl", nullSafe(seoBaseUrl));
        // 描述优先复用 AI 预检回填的 summary，缺省则从正文纯文本截取，保证 <meta description> 非空
        model.addAttribute("metaDescription", buildMetaDescription(article.getSummary(), plainContent));
        model.addAttribute("coverImage", article.getCoverImage() != null ? article.getCoverImage() : "");
        model.addAttribute("publishTimeIso", toIso(article.getPublishTime()));

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

        // 职位/公司 + 真实头像（用户公开信息；优先实时头像，其次文章冗余头像，最后占位图）
        String position = "";
        String company = "";
        String userAvatar = "";
        try {
            ResponseResult userResult = userClient.getPublicInfo(authorId);
            if (userResult != null && userResult.getCode() == 200 && userResult.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> userData = (Map<String, Object>) userResult.getData();
                position = userData.get("position") != null ? userData.get("position").toString() : "";
                company = userData.get("company") != null ? userData.get("company").toString() : "";
                userAvatar = userData.get("avatar") != null ? userData.get("avatar").toString() : "";
            }
        } catch (Exception e) {
            log.warn("获取作者公开信息失败, authorId={}", authorId, e);
        }
        model.addAttribute("authorJobTitle", nullSafe(position));
        model.addAttribute("authorCompany", nullSafe(company));
        // 覆盖 detail() 早前设置的作者头像：有实时头像用之，否则用文章里存的头像，再否则占位头像
        model.addAttribute("authorAvatar",
            defaultAvatar(StringUtils.isNotBlank(userAvatar) ? userAvatar : article.getAuthorImage()));

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
     *
     * @param plainContent 已清洗的正文纯文本（由调用方统一计算一次，避免重复正则开销）
     */
    private String calculateReadTime(String plainContent) {
        return String.valueOf(Math.max(1, (int) Math.ceil(plainContent.length() / 500.0)));
    }

    /**
     * 提取正文纯文本（去掉 Markdown 语法、代码块与 HTML 标签）
     */
    private String plainText(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        return content.replaceAll("#+\\s*", "")
            .replaceAll("!\\[.*?\\]\\(.*?\\)", "")
            .replaceAll("\\[.*?\\]\\(.*?\\)", "")
            .replaceAll("```[\\s\\S]*?```", "")
            .replaceAll("`[^`]*`", "")
            .replaceAll("<[^>]+>", "")
            .replaceAll("\\s+", "");
    }

    /**
     * 构建文章 meta description：优先 AI 预检回填的 summary，缺省时从正文纯文本截取，最长 150 字
     *
     * @param plainContent 已清洗的正文纯文本（由调用方统一计算一次，避免重复正则开销）
     */
    private String buildMetaDescription(String summary, String plainContent) {
        String base = StringUtils.isNotBlank(summary)
            ? plainText(summary)
            : plainContent;
        if (StringUtils.isBlank(base)) {
            return "逐日 Coding 开发者技术社区";
        }
        return base.length() > 150 ? base.substring(0, 150) + "…" : base;
    }

    /** 发布时间转 ISO-8601（Asia/Shanghai，供 JSON-LD datePublished / sitemap lastmod 使用） */
    private String toIso(Date date) {
        if (date == null) {
            return "";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(date);
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    /** 默认占位头像（灰色圆底 SVGRect），作者头像为空时使用，保证始终可显示 */
    private static final String DEFAULT_AVATAR =
        "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' fill='%23e0e4ec'/%3E%3Ctext x='50' y='62' font-size='44' text-anchor='middle' fill='%23aab2bf'%3E%E9%BB%98%3C/text%3E%3C/svg%3E";

    /** 返回合法的头像 URL，空字符串/空白时回退到占位头像 */
    private String defaultAvatar(String avatar) {
        return StringUtils.isBlank(avatar) ? DEFAULT_AVATAR : avatar;
    }
}