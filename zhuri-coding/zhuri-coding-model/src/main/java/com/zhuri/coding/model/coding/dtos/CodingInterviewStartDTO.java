package com.zhuri.coding.model.coding.dtos;

import lombok.Data;

import java.io.Serializable;

/**
 * 模拟面试开面入参（Coding 延展第三层 · Stage A）
 *
 * <p>direction 为技术方向（如 "Java 后端"，≤64 字）；difficulty 缺省按进阶（2）；
 * questionCount 缺省取服务端配置（5），超出 3~10 由服务端夹取。</p>
 *
 * <p>简历可选：<b>只作用于本次开面，不落库</b>。上传文件走
 * {@code POST /api/v1/coding/interview/resume/parse} 解析出文本、前端回填可编辑后放在
 * resumeText 里提交；也可以直接粘贴。给了简历则提纲里会安排约 60% 的简历深挖题。</p>
 */
@Data
public class CodingInterviewStartDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 面试方向（技术栈/岗位） */
    private String direction;

    /** 难度：1入门 2进阶 3挑战（缺省 2） */
    private Integer difficulty;

    /** 主题数（3~10，缺省 5）；每主题 1 道主问题 + 最多 1 次追问 */
    private Integer questionCount;

    /** 简历文本（可选，≤8000 字；超出由服务端截断） */
    private String resumeText;
}