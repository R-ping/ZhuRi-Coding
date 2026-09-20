package com.heima.model.search.vos;

import lombok.Data;

/**
 * 文章目录项
 */
@Data
public class TocItem {

    /**
     * 锚点 id
     */
    private String id;

    /**
     * 标题级别：1/2/3
     */
    private Integer level;

    /**
     * 标题文本
     */
    private String text;
}
