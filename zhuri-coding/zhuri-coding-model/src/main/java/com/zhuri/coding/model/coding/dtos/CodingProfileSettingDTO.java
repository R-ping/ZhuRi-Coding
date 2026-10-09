package com.zhuri.coding.model.coding.dtos;

import lombok.Data;

import java.io.Serializable;

/**
 * 能力档案隐私开关保存入参（字段为空表示不修改）
 */
@Data
public class CodingProfileSettingDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总开关：是否公开档案 */
    private Boolean isPublic;

    /** 技术领域分布是否公开 */
    private Boolean publicDomain;

    /** 持续度是否公开 */
    private Boolean publicStreak;

    /** 输出能力是否公开 */
    private Boolean publicOutput;

    /** 解决问题是否公开（预留） */
    private Boolean publicSolve;

    /** 测评成绩是否公开 */
}