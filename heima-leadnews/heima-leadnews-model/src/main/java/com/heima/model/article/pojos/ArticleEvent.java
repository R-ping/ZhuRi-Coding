package com.heima.model.article.pojos;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data
@TableName("article_event")
public class ArticleEvent {

    private Long id;

    /**
     * 文章id
     */
    private Long articleId;

    private Byte retryCount; // 重试次数，默认0，理想情况minio、es都可重试1次
    private Byte maxRetryCount; // 最大重试次数
    private Date retryTime; // 重试时间

    private Byte minioStatus; // minio状态，0初始化，1为还未成功，2已成功

    private Byte esStatus; // es状态，0初始化，1为还未成功，2已成功

    /**
     * 发布状态 0=初始化 1=待重试 2=成功
     */
    private Byte pubStatus;

    private String parameter;

    private Date createTime;

    private Date updateTime;
}
