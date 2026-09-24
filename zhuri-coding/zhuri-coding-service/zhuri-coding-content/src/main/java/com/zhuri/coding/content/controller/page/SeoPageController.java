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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * 站点 SEO 基础文件控制器（P0 收录基建）
 *
 * <p>经网关对外路径固定为 <b>/content/robots.txt</b> 、 <b>/content/sitemap.xml</b>
 * （网关 StripPrefix 后落到本服务的 /robots.txt、/sitemap.xml），并已在网关白名单放行，爬虫可匿名读取。
 *
 * <p>两者均与正式域名解耦：绝对 URL 前缀取自配置 {@code app.seo.base-url}
 * （正式部署用环境变量 SEO_BASE_URL 注入）。未配置域名时：
 * <ul>
 *   <li>sitemap 的 &lt;loc&gt; 输出相对路径（便于本地调试）；</li>
 *   <li>robots <b>不输出</b> Sitemap 指令 —— robots 协议（RFC 9309）要求其为绝对 URL，
 *       相对路径会被搜索引擎忽略，输出反而可能被判定为无效声明。</li>
 * </ul>
 */
@Controller
@Slf4j
public class SeoPageController {

    /** sitemap 输出上限（Google 单文件上限 50k，这里严格控制防止全量拉取拖垮库） */
    private static final int SITEMAP_MAX_ENTRIES = 10000;

    /** sitemap 结果缓存时长：爬虫会周期性抓取，避免每次请求都全量查库 + 拼串 */
    private static final long SITEMAP_CACHE_TTL_MS = 30 * 60 * 1000L;

    /** sitemap lastmod 格式化（线程安全可静态复用；sitemap 协议只需日期粒度） */
    private static final DateTimeFormatter SITEMAP_DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Shanghai"));

    @Autowired
    private ApArticleMapper apArticleMapper;

    /** 站点对外域名（canonical / OG / sitemap 绝对 URL 前缀；空时输出相对路径） */
    @Value("${app.seo.base-url:}")
    private String seoBaseUrl;

    /** sitemap 缓存（不可变条目整体替换，保证内容与生成时间始终一致；volatile 保证可见性） */
    private record SitemapCache(String content, long generatedAt) {
        /** 是否仍在有效期内 */
        boolean fresh() {
            return System.currentTimeMillis() - generatedAt < SITEMAP_CACHE_TTL_MS;
        }
    }

    private final Object sitemapLock = new Object();
    private volatile SitemapCache sitemapCache;

    /**
     * robots.txt：未配置对外域名时只输出 User-agent / Allow，不输出 Sitemap 指令
     * （RFC 9309 要求 Sitemap 为绝对 URL，相对路径会被搜索引擎忽略）
     */
    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public String robots() {
        StringBuilder sb = new StringBuilder();
        sb.append("User-agent: *\n").append("Allow: /\n").append("\n");
        if (StringUtils.isBlank(seoBaseUrl)) {
            log.debug("[SEO] 未配置 SEO_BASE_URL，robots.txt 跳过 Sitemap 指令（正式部署请设置）");
            return sb.toString();
        }
        return sb.append("Sitemap: ").append(seoBaseUrl).append("/content/sitemap.xml\n").toString();
    }

    /**
     * 动态站点地图：仅收录已发布、未删除的文章（status=9），按发布时间倒序，最多 10k 条。
     *
     * <p>结果按 {@link #SITEMAP_CACHE_TTL_MS} 缓存，命中直接返回，避免爬虫高频抓取反复查库；
     * 缓存失效时用双重检查锁保证并发下只由一个线程重建。
     */
    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    @ResponseBody
    public String sitemap() {
        SitemapCache cache = sitemapCache;
        if (cache != null && cache.fresh()) {
            return cache.content();
        }
        synchronized (sitemapLock) {
            // 双重检查：并发未命中时只由一个线程生成，其余线程复用结果
            cache = sitemapCache;
            if (cache != null && cache.fresh()) {
                return cache.content();
            }
            SitemapCache fresh = new SitemapCache(buildSitemap(), System.currentTimeMillis());
            sitemapCache = fresh;
            return fresh.content();
        }
    }

    /** 查询已发布文章并拼装 sitemap XML（仅在缓存失效时调用） */
    private String buildSitemap() {
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
        // 仅在真正重建时打印（缓存命中不打），避免爬虫高频抓取造成日志噪音
        log.info("生成 sitemap.xml, 收录文章 {} 篇", articles.size());
        return sb.toString();
    }

    /** 发布时间转 ISO-8601 日期（yyyy-MM-dd，sitemap lastmod 仅需日期粒度） */
    private String toIsoDate(Date date) {
        if (date == null) {
            return "";
        }
        return SITEMAP_DATE_FMT.format(date.toInstant());
    }
}
