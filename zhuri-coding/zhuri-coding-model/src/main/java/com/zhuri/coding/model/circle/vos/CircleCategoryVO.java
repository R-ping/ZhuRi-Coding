package com.zhuri.coding.model.circle.vos;

import lombok.Data;

import java.io.Serializable;

@Data
public class CircleCategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name = "";
    private Integer sortOrder = 0;
    private Integer circleCount = 0;
}