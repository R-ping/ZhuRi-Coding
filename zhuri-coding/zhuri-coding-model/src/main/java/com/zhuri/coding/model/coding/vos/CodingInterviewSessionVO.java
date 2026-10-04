package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 模拟面试会话视图（开面/进行中返回，Coding 延展第三层 · Stage A）
 *
 * <p><b>不下发完整提纲</b>：只给当前主题与进度，其余主题问题不下发，避免用户提前看到全部问题；
 * 提纲的 keyPoints（关键考点）仅服务端可见，报告覆盖度判定以此为准。</p>
 *
 * <p>current 接口额外携带 {@code turns}（全量对话流水），供刷新/换设备后恢复。</p>
 */
@Data
public class CodingInterviewSessionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 面试记录ID */
    private Long interviewId;

    /** 面试方向 */
    private String direction;

    /** 难度：1入门 2进阶 3挑战 */
    private Integer difficulty;

    /** 状态：1进行中 2已完成 3已过期 */
    private Integer status;

    /** 主题总数 */
    private Integer totalTopics;

    /** 当前主题下标（从0起） */
    private Integer currentIndex;

    /** 当前主题（题目/追问对象） */
    private CurrentTopic currentTopic;

    /** 开面时间（yyyy-MM-dd HH:mm:ss，展示用） */
    private String startedTime;

    /** 截止时间（同上） */
    private String deadlineTime;

    /** 限时总秒数 */
    private Integer durationSeconds;

    /** 剩余作答秒数（倒计时以服务端为准，避免客户端时钟偏差） */
    private Integer remainingSeconds;

    /** 对话流水（仅 current 返回；role: interviewer|user，type: question|followup|answer） */
    private List<TurnItem> turns;

    @Data
    public static class CurrentTopic implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 主题下标（从0起） */
        private Integer index;

        /** 主题名 */
        private String topic;

        /** 当前应回答的问题（主问题或最近一次追问） */
        private String question;
    }

    @Data
    public static class TurnItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /** interviewer=面试官 user=用户 */
        private String role;

        /** question=主问题 followup=追问 answer=作答 */
        private String type;

        /** 文本内容 */
        private String content;

        /** 所属主题下标 */
        private Integer topicIndex;

        /** 时间戳（毫秒） */
        private Long ts;
    }
}