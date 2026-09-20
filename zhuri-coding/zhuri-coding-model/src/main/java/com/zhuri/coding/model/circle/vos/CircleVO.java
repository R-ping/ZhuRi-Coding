package com.heima.model.circle.vos;

import lombok.Data;

import java.io.Serializable;

@Data
public class CircleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name = "";
    private String description = "";
    private String icon = "";
    private Integer memberCount = 0;
    private Integer pinsCount = 0;
    private Boolean isJoined = false;
}