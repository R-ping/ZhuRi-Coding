package com.heima.model.article.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("ap_article_audit_record")
public class ApArticleAuditRecord implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Long authorId;
    private String title;
    private String content;
    private String reason;
    private String auditType;
    private Integer status;
    private LocalDateTime createdAt;
}