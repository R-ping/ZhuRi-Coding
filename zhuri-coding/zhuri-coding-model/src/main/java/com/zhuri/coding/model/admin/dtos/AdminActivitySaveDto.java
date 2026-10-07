package com.zhuri.coding.model.admin.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 活动新建 / 编辑入参。
 *
 * <p><b>为什么新建与编辑共用一个 DTO</b>：两者要填的字段完全一样（标题、描述、封面、
 * 类型、分类、起止日期、关联话题）。分成两个 DTO 的唯一后果是将来加字段要改两处，
 * 而漏掉一处会表现为"新建能填、编辑填不了"这种只有用了才发现的不一致。
 *
 * <p><b>为什么日期是字符串而不是 {@code Date}</b>：{@code start_date / end_date} 是
 * {@code DATE} 列（没有时刻），而 Jackson 反序列化 {@code Date} 要经过一层时区换算 ——
 * 客户端发 {@code "2026-10-06"}，落到库里可能是 10-05 也可能就是 10-06，取决于
 * 反序列化配置与 JVM 时区的组合。这种"差一天"的偏差在日期字段上尤其难发现：
 * 页面显示正常，只是活动提前一天结束。收成 {@code yyyy-MM-dd} 字符串由服务端显式解析，
 * 就没有这层换算可言了。
 *
 * <p>日期格式固定为 {@code yyyy-MM-dd}，格式不对会得到一句明确的参数错误，
 * 而不是一个含义不明的反序列化失败。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminActivitySaveDto extends AdminActionDto {

    private static final long serialVersionUID = 1L;

    /** 活动标题（必填；上限 200，与 {@code ap_activity.title} 列等长） */
    private String title;

    /** 活动描述；可空。前端传空串表示"清空"，服务端统一归为 null。 */
    private String description;

    /** 封面图 URL；可空（上限 255，与列等长） */
    private String coverImage;

    /** 活动类型：{@code article} / {@code pin}；不传用列默认值 {@code article} */
    private String activityType;

    /** 活动分类：{@code hot/backend/frontend/android/ios/ai/devtools/codelife}；不传用列默认值 {@code hot} */
    private String category;

    /** 开始日期，{@code yyyy-MM-dd}（必填） */
    private String startDate;

    /** 结束日期，{@code yyyy-MM-dd}（必填，且不得早于开始日期） */
    private String endDate;

    /** 关联话题ID；不传或传 null 表示不关联（注意 {@code 0} 不是"不关联"，它会被判为非法 ID） */
    private Long topicId;
}
