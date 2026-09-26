package com.zhuri.coding.model.article.pojos;

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

    /**
     * 任务状态（单一状态机，替代原 es_status/pub_status 双状态位）：
     * 1=INIT 已落消息待处理；2=DB_SET_FAIL 文章可见态(DB PUBLISHED)置位失败，待扫描重试；
     * 3=ES_SYNC_FAIL 文章已发布但 ES 同步失败，待扫描重试；4=DONE 全部完成可删除。
     */
    private Byte status;

    private Byte retryCount; // ES 同步失败连续重试次数（DB_SET_FAIL 幂等自愈，不计入）
    private Byte maxRetryCount; // ES 同步最大重试次数
    private Date retryTime; // 下次重试时间

    private String parameter;

    private Date createTime;

    private Date updateTime;
}
