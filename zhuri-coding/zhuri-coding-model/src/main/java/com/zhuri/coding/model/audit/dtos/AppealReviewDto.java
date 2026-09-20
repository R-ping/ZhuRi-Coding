package com.zhuri.coding.model.audit.dtos;

import lombok.Data;

/**
 * 内容治理申诉人工终审请求
 */
@Data
public class AppealReviewDto {

    /** 申诉记录ID */
    private Long appealId;

    /** 终审动作：allow-解除处置 / uphold-维持原处置 */
    private String action;

    /** 终审备注（可空） */
    private String remark;
}
