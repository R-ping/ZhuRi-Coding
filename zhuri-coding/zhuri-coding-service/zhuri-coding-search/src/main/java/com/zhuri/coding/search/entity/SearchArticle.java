package com.zhuri.coding.search.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@Document(indexName = "app_info_article")
public class SearchArticle {

    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String title;

    @Field(type = FieldType.Date)
    private Date publishTime;

    @Field(type = FieldType.Integer)
    private Integer layout;

    @Field(type = FieldType.Keyword)
    private String images;

    private Long authorId;

    @Field(type = FieldType.Keyword)
    private String authorName;

    private String staticUrl;
    private String fileName;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String content;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String htmlContent;

    private List<Map<String, Object>> tocList;
    private List<Map<String, Object>> authorWorks;

    @Field(type = FieldType.Integer)
    private Integer status;

    /**
     * 可见性标记：内容下线时先把本字段置 true，再物理删除文档。
     *
     * <p>检索侧的过滤条件是「排除 isDeleted=true」，因此标记一旦写入，文档立刻不再出现在结果里 ——
     * 后续那条 delete 只是清理，失败也不会漏出可见内容。
     */
    @Field(type = FieldType.Boolean)
    private Boolean isDeleted;
}