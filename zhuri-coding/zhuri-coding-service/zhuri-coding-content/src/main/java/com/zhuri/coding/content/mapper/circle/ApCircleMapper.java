package com.zhuri.coding.content.mapper.circle;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.circle.pojos.ApCircle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ApCircleMapper extends BaseMapper<ApCircle> {

    List<ApCircle> selectRecommendCircles(@Param("limit") int limit);

    List<ApCircle> selectSquareCircles(@Param("offset") int offset, @Param("size") int size);

    long selectSquareCirclesCount();

    /**
     * 原子自增成员数（逐友数）。
     *
     * <p><b>为什么不用"查出来 +1 再写回"</b>：那是读改写，两个并发加入都读到旧值、各自写回 +1，
     * 结果只加了 1（<b>丢失更新</b>）。{@code @Transactional} 只保证原子提交，<b>并不解决丢失更新</b>。
     * {@code COALESCE} 兜底 NULL（该列 DDL 为 {@code int DEFAULT '0'} 但未声明 NOT NULL）。
     */
    @Update("UPDATE ap_circle SET member_count = COALESCE(member_count, 0) + 1 WHERE id = #{circleId}")
    int incrementMemberCount(@Param("circleId") Long circleId);

    /** 原子自减成员数，下限夹到 0（与原先 {@code Math.max(0, mc - 1)} 语义一致） */
    @Update("UPDATE ap_circle SET member_count = GREATEST(COALESCE(member_count, 0) - 1, 0) WHERE id = #{circleId}")
    int decrementMemberCount(@Param("circleId") Long circleId);
}