package com.zhuri.coding.content.controller.page;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.model.article.pojos.ApArticle;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * 站点 SEO 基础文件控制器（P0 收录基建）
 *
 * <p>经网关对外路径固定为 <b>/content/robots.txt</b> 、 <b>/content/sitemap.xml</b>
 * （网关 StripPrefix 后落到本服务的 /robots.txt、/sitemap.xml），并已在网关白名单放行，爬虫可匿名读取。
 *
 * <p>两个文件均与正式域名解耦：绝对 URL 前缀取自配置 {@code app.seo.base-url}
 * （正式部署用环境变量 SEO_BASE_URL 注入），本地开发为空时输出相对路径便于调试。
 */
@Controller
@Slf4j
public class SeoPageController {

    /** sitemap 输出上限（Google 单文件上限 50k，这里严格控制防止全量拉取拖垮库） */
    private static final int SITEMAP_MAX_ENTRIES = 10000;

    @Autowired
    private ApArticleMapper apArticleMapper;

    /** 站点对外域名（canonical / OG / sitemap 绝对 URL 前缀；空时输出相对路径） */
    @Value("${app.seo.base-url:}")
    private String seoBaseUrl;

    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String robots() {
        String sitemap = seoBaseUrl + "/content/sitemap.xml";
        return "User-agent: *\n"
            + "Allow: /\n"
            + "\n"
            // 未配置正式域名时 Sitemap 输出相对路径（仅便于本地/联调验证，搜索引擎以绝对地址为准）
            + (StringUtils.isBlank(seoBaseUrl) ? "# 未配置 SEO_BASE_URL（正式部署请设置），Sitemap 以相对路径输出\n" : "")
            + "Sitemap: " + sitemap + "\n";
    }

    /**
     * 动态站点地图：仅收录已发布、未删除的文章（status=9），按发布时间倒序，最多 10k 条
     */
    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    @ResponseBody
    public String sitemap() {
        List<ApArticle> articles = apArticleMapper.selectList(
            new LambdaQueryWrapper<ApArticle>()
                // 仅取 id / publish_time 两列，避免全行查询把 tags/contPics 等大 JSON 列拖回内存
                .select(ApArticle::getId, ApArticle::getPublishTime)
                .eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode())
                .eq(ApArticle::getIsDeleted, false)
                .orderByDesc(ApArticle::getPublishTime)
                .last("LIMIT " + SITEMAP_MAX_ENTRIES));

        StringBuilder sb = new StringBuilder(160 + articles.size() * 120);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (ApArticle a : articles) {
            sb.append("  <url>\n");
            sb.append("    <loc>").append(seoBaseUrl).append("/content/article/").append(a.getId()).append("</loc>\n");
            String lastmod = toIsoDate(a.getPublishTime());
            if (StringUtils.isNotBlank(lastmod)) {
                sb.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
            }
            sb.append("  </url>\n");
        }
        sb.append("</urlset>\n");
        log.info("生成 sitemap.xml, 收录文章 {} 篇", articles.size());
        return sb.toString();
    }

    /** 发布时间转 ISO-8601 日期（yyyy-MM-dd，sitemap lastmod 仅需日期粒度） */
    private String toIsoDate(Date date) {
        if (date == null) {
            return "";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(date);
    }
}