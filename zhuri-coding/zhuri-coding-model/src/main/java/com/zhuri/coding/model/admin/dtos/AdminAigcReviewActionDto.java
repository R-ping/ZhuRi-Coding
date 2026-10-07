package com.zhuri.coding.model.admin.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AIGC 复核动作入参（放行 / 确认共用）。
 *
 * <p><b>为什么没有额外字段还要单独建一个 DTO</b>：动作语义自描述 ——
 * 控制器方法签名上写 {@code AdminAigcReviewActionDto}，读代码的人立刻知道
 * 这个请求是"复核"而不是任何别的操作；共用 {@code AdminActionDto} 的话，
 * 将来复核需要专属字段（如复核结论备注）时才临时建类，前后端要同时换类型。
 * 现在就立住类型，代价只有十行。
 *
 * <p>{@code reason}（继承自基类）必填：放行会撤销机器判定、确认会把"疑似"钉死成
 * "确认水文"，两者都是会影响作者收益的动作，事后追问全靠这句话。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminAigcReviewActionDto extends AdminActionDto {

    private static final long serialVersionUID = 1L;
}
