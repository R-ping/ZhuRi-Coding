package com.heima.content.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.user.pojos.UserScoreDetails;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface UserScoreDetailsMapper extends BaseMapper<UserScoreDetails> {

    // 游标分页查询
    List<UserScoreDetails> selectByCursor(
        @Param("userId") Long userId,
        @Param("category") Integer category,
        @Param("cursorCreatedAt") String cursorCreatedAt,
        @Param("cursorId") Long cursorId,
        @Param("size") Integer size
    );

    // 查询今日累计
    @Select("SELECT COALESCE(SUM(score), 0) FROM user_score_details WHERE user_id = #{userId} AND category = #{category} AND created_at >= #{todayStart}")
    BigDecimal sumTodayScoreByCategory(@Param("userId") Long userId, @Param("category") Integer category, @Param("todayStart") String todayStart);
}