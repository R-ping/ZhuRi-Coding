package com.heima.content.mapper.pins;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.pins.pojos.ApPins;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApPinsMapper extends BaseMapper<ApPins> {

    /** 原子递增点赞数 */
    @Update("UPDATE ap_pins SET likes = likes + 1 WHERE id = #{id}")
    int incrementLikes(@Param("id") Long id);

    /** 原子递减点赞数（不低于0） */
    @Update("UPDATE ap_pins SET likes = likes - 1 WHERE id = #{id} AND likes > 0")
    int decrementLikes(@Param("id") Long id);

    /** 原子递增评论数 */
    @Update("UPDATE ap_pins SET comment = comment + 1 WHERE id = #{id}")
    int incrementComment(@Param("id") Long id);

    /** 原子递增分享数 */
    @Update("UPDATE ap_pins SET share_count = share_count + 1 WHERE id = #{id}")
    int incrementShare(@Param("id") Long id);
}