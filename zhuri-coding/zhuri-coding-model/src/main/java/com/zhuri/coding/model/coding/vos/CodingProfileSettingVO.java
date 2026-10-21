package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 能力档案隐私开关（读取/保存回显）
 */
@Data
public class CodingProfileSettingVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总开关：是否公开档案（默认 false） */
    private Boolean isPublic;

    /** 技术领域分布是否公开（默认 true） */
    private Boolean publicDomain;

    /** 持续度是否公开（默认 true） */
    private Boolean publicStreak;

    /** 输出能力是否公开（默认 true） */
    private Boolean publicOutput;

    /** 解决问题是否公开（依赖付费问答，预留恒 false） */
    private Boolean publicSolve;

    /** 测评成绩是否公开（默认 true） */
}