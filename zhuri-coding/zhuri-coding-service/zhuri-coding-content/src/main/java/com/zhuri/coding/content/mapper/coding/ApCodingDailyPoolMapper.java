package com.zhuri.coding.content.mapper.coding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingDailyPool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApCodingDailyPoolMapper extends BaseMapper<ApCodingDailyPool> {

    /**
     * 抽一道题：方向命中优先，按 use_count 升序再随机，让冷门题也能轮到。
     *
     * <p>不走"未答过优先"：简答题重复练仍有价值，不需要去重。
     * {@code ORDER BY RAND()} 在这里可接受 —— 一个方向的池子只有几十道，
     * 且每人每天只抽一次（命中 Redis 缓存后不再查库）。上量需换"随机偏移 + LIMIT 1"两步法。</p>
     */
    @Select("SELECT * FROM ap_coding_daily_pool "
        + "WHERE direction = #{direction} AND status = 1 "
        + "ORDER BY use_count ASC, RAND() LIMIT 1")
    ApCodingDailyPool selectOneByDirection(@Param("direction") String direction);

    /** 不限方向兜底：用户填的方向池子里没题时，也让他有题可做 */
    @Select("SELECT * FROM ap_coding_daily_pool "
        + "WHERE status = 1 ORDER BY use_count ASC, RAND() LIMIT 1")
    ApCodingDailyPool selectOneAny();

    /** 抽中后累加被抽次数（轮转依据） */
    @Update("UPDATE ap_coding_daily_pool SET use_count = use_count + 1 WHERE id = #{id}")
    int incrementUseCount(@Param("id") Long id);
}
