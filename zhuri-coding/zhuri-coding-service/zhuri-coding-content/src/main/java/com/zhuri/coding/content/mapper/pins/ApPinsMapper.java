package com.zhuri.coding.content.mapper.pins;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.pins.pojos.ApPins;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 沸点计数列的原子自增/自减。
 *
 * <p><b>两条容易踩的坑，这里都已处理</b>：
 * <ol>
 *   <li><b>列名</b>：真实列名是 {@code like_count} / {@code comment_count}（见 {@code ApPins} 的
 *       {@code @TableField} 与 DDL）。此前 {@code incrementLikes} 写成 {@code likes}、
 *       {@code incrementComment} 写成 {@code comment}，一旦被调用即 SQL 报错——而这两个方法
 *       <b>恰好一直无人调用</b>，所以错误静默潜伏至今。现已校正。</li>
 *   <li><b>NULL 语义</b>：这些列 DDL 为 {@code int DEFAULT '0'} 但<b>未声明 NOT NULL</b>，
 *       历史行可能为 NULL；而 MySQL 中 {@code NULL + 1 = NULL}，会把计数直接写成 NULL。
 *       故统一用 {@code COALESCE(col, 0)} 兜底；递减再套 {@code GREATEST(..., 0)} 保证不为负。</li>
 * </ol>
 */
@Mapper
public interface ApPinsMapper extends BaseMapper<ApPins> {

    /** 原子递增点赞数 */
    @Update("UPDATE ap_pins SET like_count = COALESCE(like_count, 0) + 1 WHERE id = #{id}")
    int incrementLikes(@Param("id") Long id);

    /** 原子递减点赞数（不低于 0） */
    @Update("UPDATE ap_pins SET like_count = GREATEST(COALESCE(like_count, 0) - 1, 0) WHERE id = #{id}")
    int decrementLikes(@Param("id") Long id);

    /** 原子递增评论数 */
    @Update("UPDATE ap_pins SET comment_count = COALESCE(comment_count, 0) + 1 WHERE id = #{id}")
    int incrementComment(@Param("id") Long id);

    /** 原子递增分享数 */
    @Update("UPDATE ap_pins SET share_count = COALESCE(share_count, 0) + 1 WHERE id = #{id}")
    int incrementShare(@Param("id") Long id);

    /** 原子递增浏览量 */
    @Update("UPDATE ap_pins SET view_count = COALESCE(view_count, 0) + 1 WHERE id = #{id}")
    int incrementViews(@Param("id") Long id);
}