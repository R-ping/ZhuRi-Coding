package com.heima.model.search.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
public class SearchArticleVo implements Serializable {

    // 文章id
    private Long id;
    // 文章标题
    private String title;
    // 文章发布时间
    private Date publishTime;
    // 文章布局
    private Integer layout;
    // 封面
    private String images;
    // 作者id
    private Long authorId;
    // 作者名词
    private String authorName;
    //静态url
    private String staticUrl;
    // "yyyy/MM/dd/articleId"
    private String fileName;
    //文章内容（原始 Markdown）
    private String content;
    // 渲染后的 HTML 内容
    private String htmlContent;
    // 文章目录
    private List<TocItem> tocList;
    // 作者作品列表
    private List<Map<String, Object>> authorWorks;

}