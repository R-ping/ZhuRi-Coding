package com.zhuri.coding.model.admin.dtos;

import com.zhuri.coding.model.admin.AdminContentType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 折叠/解除折叠入参。
 *
 * <p>{@code targetType} 必填：折叠动作落在哪张表上（文章评论 / 沸点评论）决定了后续所有事
 * —— 更新哪一行、通知谁、审计里的 target_type 写什么。给个默认值省事，但省掉的是一次
 * "本来会失败的操作被悄悄执行到了另一张表上"的机会，所以宁可让调用方写清楚。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CommentFoldDto extends AdminActionDto {

    private static final long serialVersionUID = 1L;

    /** 折叠对象类型：COMMENT 文章评论 / PINS_COMMENT 沸点评论 */
    private String targetType;

    /** 允许的类型编码（由枚举派生，避免这里写死一份、枚举新增后两边对不上） */
    public static final Set<String> ALLOWED_TARGETS = Arrays.stream(AdminContentType.values())
        .map(Enum::name)
        .collect(Collectors.toUnmodifiableSet());
}
