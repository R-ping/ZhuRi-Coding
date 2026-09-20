package com.zhuri.coding.model.article.vos;

import lombok.Data;

import java.io.Serializable;

@Data
public class TagVO implements Serializable {

    private String tagId;
    private String tagName;
    private String color;
}