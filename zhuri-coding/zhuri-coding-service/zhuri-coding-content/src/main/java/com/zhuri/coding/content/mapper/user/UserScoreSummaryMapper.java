package com.heima.content.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.user.pojos.UserScoreSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.math.BigDecimal;
import java.util.Set;

@Mapper
public interface UserScoreSummaryMapper extends BaseMapper<UserScoreSummary> {

    /** ${field} 动态列名白名单：仅允许汇总表既有分类列，杜绝未来误用导致的 SQL 拼接 */
    Set<String> ALLOWED_FIELD_COLUMNS = Set.of(
        "total_score", "basic_score", "active_score", "learn_score", "effect_score", "spec_score");

    @Select("SELECT COALESCE(SUM(total_score), 0) FROM user_score_summary WHERE user_id = #{userId}")
    BigDecimal sumTotalScore(@Param("userId") Long userId);

    /**
     * 按分类列求和（${field} 动态列名，白名单校验后转发到内部查询，防止 SQL 注入）
     */
    default BigDecimal sumFieldScore(Long userId, String field) {
        if (field == null || !ALLOWED_FIELD_COLUMNS.contains(field)) {
            throw new IllegalArgumentException("非法的汇总字段: " + field);
        }
        return sumFieldScoreRaw(userId, field);
    }

    @Select("SELECT COALESCE(SUM(${field}), 0) FROM user_score_summary WHERE user_id = #{userId}")
    BigDecimal sumFieldScoreRaw(@Param("userId") Long userId, @Param("field") String field);

    /**
     * 按日 upsert：当日记录不存在则插入，存在则在对应分类列上原子累加增量。
     * 传入除 total 与目标分类列外的其余分类列值为 0，避免覆盖。
     */
    @Insert("INSERT INTO user_score_summary (user_id, stat_date, total_score, basic_score, active_score, learn_score, effect_score, spec_score) "
        + "VALUES (#{userId}, #{statDate}, #{totalScore}, #{basicScore}, #{activeScore}, #{learnScore}, #{effectScore}, #{specScore}) "
        + "ON DUPLICATE KEY UPDATE total_score = total_score + VALUES(total_score), "
        + "basic_score = basic_score + VALUES(basic_score), "
        + "active_score = active_score + VALUES(active_score), "
        + "learn_score = learn_score + VALUES(learn_score), "
        + "effect_score = effect_score + VALUES(effect_score), "
        + "spec_score = spec_score + VALUES(spec_score)")
    int upsertDailyScore(@Param("userId") Long userId,
        @Param("statDate") java.sql.Date statDate,
        @Param("totalScore") BigDecimal totalScore,
        @Param("basicScore") BigDecimal basicScore,
        @Param("activeScore") BigDecimal activeScore,
        @Param("learnScore") BigDecimal learnScore,
        @Param("effectScore") BigDecimal effectScore,
        @Param("specScore") BigDecimal specScore);
}