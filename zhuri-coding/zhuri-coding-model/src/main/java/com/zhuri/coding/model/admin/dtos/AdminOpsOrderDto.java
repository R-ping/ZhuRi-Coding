package com.zhuri.coding.model.admin.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 运营位清单入参（人气圈子 / 推荐话题共用）。
 *
 * <p><b>为什么是「一份有序清单」而不是「单条增删改」</b>：这两处运营位在库里的形态就是
 * 「一张有序表」—— 人气圈子是 {@code ap_circle_hot_config(display_order)}，
 * 推荐话题是 {@code ap_topic(recommend_sort)}，C 端读的时候都是"按顺序取前 N 条"。
 * 顺序是这份配置唯一的语义，而顺序只能在整份清单上表达：单条"把 A 改成第 2 位"必然
 * 要连带挪动后面的人，接口却只描述了一次改动，剩下那些"顺带发生的移动"就没有任何地方留痕。
 *
 * <p><b>数组顺序即展示顺序</b>：调用方不需要（也不应该）传 display_order / recommend_sort，
 * 服务端按数组下标从 1 重新编号。这样客户端少一个可以传错的值，
 * 服务端也不必去校验"位次是否唯一、是否连续"这类本可以不必存在的问题。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminOpsOrderDto extends AdminActionDto {

    private static final long serialVersionUID = 1L;

    /**
     * 运营位清单（有序）。
     *
     * <p>{@code null} 与空数组含义不同：{@code null} 视为参数缺失（报错），
     * 空数组是合法的"清空运营位"（例如临时下掉全部推荐话题）。
     */
    private List<Long> items;
}
