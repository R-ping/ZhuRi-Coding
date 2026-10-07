package com.zhuri.coding.model.admin.dtos;

import com.zhuri.coding.model.behavior.pojos.ApArticleReport;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Set;

/**
 * 举报处置入参。
 *
 * <p>{@code reason} 继承自 {@link AdminActionDto} 且必填 —— 它同时承担两个用途：
 * 写进审计留痕，以及**原样回执给举报人**（举报人等了这么久，一句"已处理"没有意义，
 * 得告诉他"为什么这么判"）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReportHandleDto extends AdminActionDto {

    private static final long serialVersionUID = 1L;

    /** 处置结论：1驳回举报 2警告作者 3下架内容（见 {@link ApArticleReport} 常量） */
    private Integer handleResult;

    /** 允许的处置结论，服务端据此校验，避免前端传进一个拼接出来的值 */
    public static final Set<Integer> ALLOWED_RESULTS = Set.of(
        ApArticleReport.RESULT_REJECT,
        ApArticleReport.RESULT_WARN_AUTHOR,
        ApArticleReport.RESULT_TAKE_DOWN
    );
}
