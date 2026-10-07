package com.zhuri.coding.model.admin.vos;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 运营后台 · 活动出参。
 *
 * <p><b>与 C 端 {@code ActivityVO} 的差别</b>：这里多了三样运营才需要的东西 ——
 * 草稿与已下线（C 端根本读不到这两类）、{@code statusDesc}（把 {@code ongoing} 这类
 * 编码翻成中文，省得每个前端页面各写一份映射）、{@code topicName}（列表上只显示
 * {@code topicId=18} 的话，运营没法确认自己关联的话题对不对）。
 *
 * <p><b>为什么日期在这里又变回字符串</b>：{@code start_date / end_date} 是 {@code DATE} 列，
 * 序列化成 {@code 2026-10-06T00:00:00.000+08:00} 会让前端的日期控件多一道切割，
 * 而这个字段本来就没有时刻信息。固定成 {@code yyyy-MM-dd} 之后，前端拿到的就是可直接
 * 回填到输入框、也可直接回传给保存接口的值（保存接口收的正是同一个格式）。
 * {@code createdTime / updatedTime} 是真正的时刻，保留完整日期时间。
 */
@Data
public class AdminActivityVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    private String description;

    private String coverImage;

    /** 活动类型编码：{@code article} / {@code pin} */
    private String activityType;

    /** 活动分类编码 */
    private String category;

    /** 状态编码：{@code draft/upcoming/ongoing/ended/offline} */
    private String status;

    /** 状态中文名（由 {@code ActivityStatus.describe} 给出，含未知取值的原样透出） */
    private String statusDesc;

    /** 开始日期（{@code yyyy-MM-dd}） */
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private Date startDate;

    /** 结束日期（{@code yyyy-MM-dd}） */
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private Date endDate;

    /** 关联话题ID；未关联为 null */
    private Long topicId;

    /**
     * 关联话题名；未关联、或话题已被删除时为 null。
     *
     * <p>话题被删而活动还关联着它，是配置类数据常见的悬挂状态。这里不报错、也不隐藏，
     * 让 {@code topicId} 原样显示出来（前端可标注"话题已不存在"）——
     * 静默隐藏只会让运营对着一行"没有话题"的活动反复保存。
     */
    private String topicName;

    private Integer totalParticipants;

    private Long totalReadCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createdTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updatedTime;
}
