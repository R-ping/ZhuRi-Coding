package com.heima.content.mapper.circle;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.circle.pojos.ApCircle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApCircleMapper extends BaseMapper<ApCircle> {

    List<ApCircle> selectRecommendCircles(@Param("limit") int limit);

    List<ApCircle> selectSquareCircles(@Param("offset") int offset, @Param("size") int size);

    long selectSquareCirclesCount();
}