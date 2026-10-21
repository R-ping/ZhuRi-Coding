package com.zhuri.coding.model.search.dtos;

import java.io.Serializable;
import lombok.Data;

/**
 * 反向对账的取数条件：把索引里「发布时间落在窗口内」的文章 id 取回来。
 *
 * <p>正向对账是「库里有、索引没有」，问的是给定 id 缺不缺；反向对账是「索引有、库里不该有」，
 * 库里没有候选集可给，只能由索引侧按时间窗先列出自己的清单。
 */
@Data
public class IndexedArticleQueryDto implements Serializable {

    /** 发布时间下界（毫秒时间戳），与正向对账用同一个窗口 */
    private Long sinceMillis;

    /** 单轮取回上限，控制 ES 查询量 */
    private Integer limit;
}
