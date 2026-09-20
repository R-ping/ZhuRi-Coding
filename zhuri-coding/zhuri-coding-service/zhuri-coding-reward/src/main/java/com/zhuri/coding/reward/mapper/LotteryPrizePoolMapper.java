package com.zhuri.coding.reward.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.reward.entity.LotteryPrizePool;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface LotteryPrizePoolMapper extends BaseMapper<LotteryPrizePool> {

    /**
     * 原子占用一件实物奖品库存：total_stock > 0 才扣减（并发下由数据库保证不超发）。
     *
     * @param id 奖品ID
     * @return 影响行数（1=占用成功，0=库存已售罄）
     */
    @Update("UPDATE lottery_prize_pool SET total_stock = total_stock - 1 " +
            "WHERE id = #{id} AND total_stock > 0")
    int deductStock(@Param("id") String id);
}
