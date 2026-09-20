package com.heima.reward.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.reward.entity.LotteryDailyState;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

public interface LotteryDailyStateMapper extends BaseMapper<LotteryDailyState> {

    /**
     * 原子累加当日抽奖次数（P0-6：替代原 read-modify-write 的 updateById，防并发丢失更新）
     * @return 影响行数（0 表示当日记录不存在，由调用方走 insert 分支）
     */
    @Update("UPDATE lottery_daily_state SET draw_count = draw_count + #{delta} "
            + "WHERE user_id = #{userId} AND stat_date = #{statDate}")
    int incrDrawCount(@Param("userId") Long userId, @Param("statDate") Date statDate, @Param("delta") int delta);

    /**
     * 原子占用当日免费抽奖次数（P0-6：替代 check-then-set，条件更新保证只被占用一次）
     * @return 影响行数（0 表示免费次数已被占用或当日记录不存在）
     */
    @Update("UPDATE lottery_daily_state SET free_used = 1 "
            + "WHERE user_id = #{userId} AND stat_date = #{statDate} AND (free_used = 0 OR free_used IS NULL)")
    int markFreeUsed(@Param("userId") Long userId, @Param("statDate") Date statDate);
}
