package com.zhuri.coding.model.admin.vos;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 运营后台 · AIGC 复核队列出参。
 *
 * <p>复核场景下运营最需要的三样东西，普通检测记录接口里都没有：
 * <ul>
 *   <li>{@code contentTitle / contentExcerpt} —— 复核的本质是"人读一遍原文再下判断"，
 *       让运营为了看一眼内容再调一次 C 端接口，队列就失去了意义。标题与摘要直接随行给出
 *       （摘要截 120 字，只够"像不像水文"的直觉判断，细读靠内容详情页）；</li>
 *   <li>{@code statusDesc} —— 状态编码翻成中文，省得每个前端页面各写一份映射；</li>
 *   <li>{@code contentTypeDesc} —— 同上，1/2/3 对应 文章/沸点/课程小节。</li>
 * </ul>
 *
 * <p><b>signalsJson 原样透出</b>：它是检测的证据链（句长突发度、重复度、模板词密度、
 * 作者画像偏离度、LLM 结论），复核时"为什么机器判 87 分"就靠它回答。格式化展示是
 * 前端的事，后端不解析也不重组 —— 解析了就要为每个未知字段负责。
 */
@Data
public class AdminAigcReviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 摘要截断长度：约两行文本，只支撑"像不像水文"的直觉判断 */
    public static final int EXCERPT_MAX_LEN = 120;

    private Long id;

    /** 内容类型编码：{@code 1}-文章 {@code 2}-沸点 {@code 3}-课程小节 */
    private Integer contentType;

    /** 内容类型中文名 */
    private String contentTypeDesc;

    /** 内容主键（对应各内容主表的 id，不是记录表 id） */
    private Long contentId;

    /** 作者用户ID；检测时无法归属作者则为 0 */
    private Long authorId;

    /** 综合疑似分 0-100，越高越疑似 AI 水文 */
    private Integer score;

    /** 信号明细 JSON（原样透出，见类注释） */
    private String signalsJson;

    /** 状态编码（见 {@link #describeStatus}） */
    private Integer status;

    /** 状态中文名 */
    private String statusDesc;

    /** 内容标题；沸点无标题为 null */
    private String contentTitle;

    /** 内容摘要（正文前 120 字，代码块与换行已压缩为空格） */
    private String contentExcerpt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;

    /** 内容类型中文名；未知取值原样透出编码（后台要说真话，而不是显示"未知"掩盖异常） */
    public static String describeContentType(Integer type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case 1 -> "文章";
            case 2 -> "沸点";
            case 3 -> "课程小节";
            default -> String.valueOf(type);
        };
    }

    /**
     * 记录状态中文名。
     *
     * <p>五种状态与 {@code ap_aigc_record.status} 一一对应。队列里只会出现"已标记"，
     * 但 desc 必须把五种都写全 —— 复核动作执行后前端要拿同一份映射刷新行状态。
     */
    public static String describeStatus(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 0 -> "仅记录";
            case 1 -> "已标记";
            case 2 -> "申诉中";
            case 3 -> "复核放行";
            case 4 -> "人工确认";
            default -> String.valueOf(status);
        };
    }
}
