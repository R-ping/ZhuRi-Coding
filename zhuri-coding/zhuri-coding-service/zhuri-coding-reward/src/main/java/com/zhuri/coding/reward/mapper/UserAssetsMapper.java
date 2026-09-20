package com.zhuri.coding.reward.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.reward.entity.UserAssets;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserAssetsMapper extends BaseMapper<UserAssets> {

    /**
     * 增加用户矿石余额
     * @param userId 用户ID
     * @param amount 增加数量（正数）
     */
    @Update("UPDATE user_assets SET ore_balance = ore_balance + #{amount}, updated_at = NOW() WHERE user_id = #{userId}")
    int addOreBalance(@Param("userId") Long userId, @Param("amount") int amount);

    /**
     * 扣减用户矿石余额（带余额检查）
     * @param userId 用户ID
     * @param amount 扣减数量
     * @return 影响行数（0表示余额不足）
     */
    @Update("UPDATE user_assets SET ore_balance = ore_balance - #{amount}, updated_at = NOW() WHERE user_id = #{userId} AND ore_balance >= #{amount}")
    int deductOreBalance(@Param("userId") Long userId, @Param("amount") int amount);

    /**
     * 原子更新幸运值（条件更新，避免"读-改-写"在并发下丢失更新）
     * @param userId 用户ID
     * @param luckyValue 新的幸运值
     */
    @Update("UPDATE user_assets SET lucky_value = #{luckyValue}, updated_at = NOW() WHERE user_id = #{userId}")
    int updateLuckyValue(@Param("userId") Long userId, @Param("luckyValue") int luckyValue);
}
