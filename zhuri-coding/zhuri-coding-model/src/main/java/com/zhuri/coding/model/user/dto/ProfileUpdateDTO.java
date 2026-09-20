package com.zhuri.coding.model.user.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class ProfileUpdateDTO {

    private String username;

    private Date careerStartDate;

    private String careerDirection;

    private String position;

    private String company;

    private String website;

    private String bio;

    /** 地区 */
    private String region;

    /** 学历/学校 */
    private String education;

    /** 技术技能标签（对齐掘金 skills） */
    private List<String> skills;

    private List<Integer> tagIds;
}