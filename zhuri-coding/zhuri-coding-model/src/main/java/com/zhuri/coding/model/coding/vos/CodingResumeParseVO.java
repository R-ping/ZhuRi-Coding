package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 简历解析结果（Coding 延展第三层 · Stage A 补充）
 *
 * <p>服务端<b>不保存</b>简历：解析完直接把文本回给前端，由前端回填到可编辑输入框后随
 * 开面请求提交。chars 是清洗后的原始长度（截断前），供前端提示"内容过长已截断"。</p>
 */
@Data
public class CodingResumeParseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 解析并清洗后的简历文本（可能已截断，截断处带显式标记） */
    private String text;

    /** 截断前的字符数 */
    private Integer chars;

    /** 是否发生截断（超过服务端上限） */
    private Boolean truncated;
}
