package com.zhuri.coding.model.article.vos;

import lombok.Data;

import java.io.Serializable;

@Data
public class TocItemVO implements Serializable {

    private String id;
    private String text;
    private Integer level;
}