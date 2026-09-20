package com.heima.model.user.vo;

import lombok.Data;

import java.util.List;

@Data
public class TagGroupVO {

    private String categoryCode;

    private String categoryName;

    private List<TagVO> tags;
}