package com.zhuri.coding.model.coding.dtos;

import lombok.Data;

import java.io.Serializable;

/**
 * 模拟面试开面入参（Coding 延展第三层 · Stage A）
 *
 * <p>direction 为技术方向（如 "Java 后端"，≤64 字）；difficulty 缺省按进阶（2）。</p>
 */
@Data
public class CodingInterviewStartDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 面试方向（技术栈/岗位） */
    private String direction;

    /** 难度：1入门 2进阶 3挑战（缺省 2） */
    private Integer difficulty;
}