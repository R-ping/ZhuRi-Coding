package com.zhuri.coding.content.utils;

import com.alibaba.fastjson.JSONArray;
import com.zhuri.coding.model.search.vos.TocItem;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Markdown 渲染与目录提取工具
 */
public class MarkdownUtils {

    /** 匹配 Markdown 图片语法 ![alt](url)，并捕获 url 段，用于清洗签名参数 */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("(!\\[[^\\]]*\\]\\()([^)\\s]+)(\\))");

    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        MutableDataSet options = new MutableDataSet();
        PARSER = Parser.builder(options).build();
        RENDERER = HtmlRenderer.builder(options).build();
    }

    /**
     * 将旧版 JSON 分块内容降级转换为 Markdown；已是 Markdown 时原样返回
     */
    public static String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("[") && trimmed.contains("\"type\"") && trimmed.contains("\"value\"")) {
            try {
                List<Map> blocks = JSONArray.parseArray(trimmed, Map.class);
                StringBuilder sb = new StringBuilder();
                for (Map block : blocks) {
                    String type = (String) block.get("type");
                    String value = (String) block.get("value");
                    if ("text".equals(type) && value != null) {
                        sb.append(value).append("\n\n");
                    } else if ("image".equals(type) && value != null) {
                        sb.append("![图片](").append(cleanImageUrl(value)).append(")\n\n");
                    }
                }
                return sb.toString().trim();
            } catch (Exception e) {
                // 解析失败时按原内容返回
                return content;
            }
        }
        return content;
    }

    /**
     * 将 Markdown 转换为 HTML。
     * 渲染前会清洗正文图片URL：去掉 ? 及其往后的签名参数，保留图片原始位置。
     */
    public static String toHtml(String markdown) {
        if (markdown == null) {
            return "";
        }
        Node document = PARSER.parse(cleanImageUrls(markdown));
        return RENDERER.render(document);
    }

    /**
     * 清洗 Markdown 正文中的所有图片 URL，去掉 ? 及其往后的签名参数，保留图片在正文中的固定位置。
     * 处理前后非图片文本原样保留。
     */
    public static String cleanImageUrls(String markdown) {
        if (markdown == null) {
            return null;
        }
        Matcher m = IMAGE_PATTERN.matcher(markdown);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String url = m.group(2);
            String cleaned = cleanImageUrl(url);
            if (cleaned.equals(url)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(sb, m.group(1) + Matcher.quoteReplacement(cleaned) + m.group(3));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 去掉单个 URL 中的 ? 及其往后参数 */
    private static String cleanImageUrl(String url) {
        if (url == null) {
            return "";
        }
        int idx = url.indexOf('?');
        return idx > 0 ? url.substring(0, idx) : url;
    }

    /**
     * 从 HTML 中提取 h1/h2/h3 目录，同时为没有 id 的标题生成锚点
     */
    public static List<TocItem> extractToc(String html) {
        List<TocItem> tocList = new ArrayList<>();
        if (html == null || html.trim().isEmpty()) {
            return tocList;
        }
        Document doc = Jsoup.parseBodyFragment(html);
        Elements headings = doc.select("h1, h2, h3");
        for (Element heading : headings) {
            String text = heading.text();
            String id = heading.id();
            if (id == null || id.trim().isEmpty()) {
                id = generateAnchor(text, tocList);
            }
            TocItem item = new TocItem();
            item.setId(id);
            item.setLevel(Integer.parseInt(heading.tagName().substring(1)));
            item.setText(text);
            tocList.add(item);
        }
        return tocList;
    }

    /**
     * 为 HTML 中的 h1/h2/h3 注入 id 锚点，便于目录跳转
     */
    public static String injectHeadingAnchors(String html) {
        if (html == null || html.trim().isEmpty()) {
            return html;
        }
        Document doc = Jsoup.parseBodyFragment(html);
        Elements headings = doc.select("h1, h2, h3");
        List<TocItem> existing = new ArrayList<>();
        for (Element heading : headings) {
            String id = heading.id();
            if (id == null || id.trim().isEmpty()) {
                id = generateAnchor(heading.text(), existing);
                heading.attr("id", id);
            }
            TocItem item = new TocItem();
            item.setId(id);
            item.setText(heading.text());
            existing.add(item);
        }
        return doc.body().html();
    }

    /**
     * 生成标题锚点：保留中英文、数字，其余字符替换为连字符
     */
    private static String generateAnchor(String text, List<TocItem> existing) {
        String base = text.trim().toLowerCase()
            .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
            .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "heading";
        }
        String id = base;
        int idx = 1;
        while (containsId(existing, id)) {
            id = base + "-" + idx++;
        }
        return id;
    }

    private static boolean containsId(List<TocItem> list, String id) {
        for (TocItem item : list) {
            if (id.equals(item.getId())) {
                return true;
            }
        }
        return false;
    }
}
