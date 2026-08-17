package com.heima.content.mapper.course;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.course.pojos.ApCourseDiscount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApCourseDiscountMapper extends BaseMapper<ApCourseDiscount> {

    /**
     * 原子扣减折扣码使用次数（防止并发超卖）
     * 仅在 used_count < max_uses 时才 +1，返回受影响行数；0 表示已用完
     */
    @Update("UPDATE ap_course_discount SET used_count = used_count + 1 " +
        "WHERE code = #{code} AND used_count < max_uses")
    int incrementUsedCountAtomic(@Param("code") String code);
}