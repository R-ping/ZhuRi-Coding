package com.zhuri.coding.reward.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.reward.entity.UserVirtualAsset;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户虚拟道具持有表 Mapper
 */
public interface UserVirtualAssetMapper extends BaseMapper<UserVirtualAsset> {

    /**
     * 入账/累加虚拟道具
     * <p>单个 (userId, itemCode) 唯一，存在则数量累加，不存在则插入一条。
     *
     * @param userId   用户ID
     * @param itemCode 虚拟道具代码
     * @param itemName 道具名称
     * @param count    本次入账数量（正数）
     * @return 影响行数
     */
    @Insert("INSERT INTO user_virtual_assets (user_id, item_code, item_name, quantity, source, created_at, updated_at) " +
            "VALUES (#{userId}, #{itemCode}, #{itemName}, #{count}, 'lottery', NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE quantity = quantity + #{count}, item_name = #{itemName}, updated_at = NOW()")
    int credit(@Param("userId") Long userId, @Param("itemCode") String itemCode,
               @Param("itemName") String itemName, @Param("count") int count);

    /**
     * 核销/扣减虚拟道具（带数量守卫，防止扣成负数）
     *
     * @param userId   用户ID
     * @param itemCode 虚拟道具代码
     * @param count    本次核销数量（正数）
     * @return 影响行数（0 表示道具不存在或数量不足；扣减成功则数量恰好减 count）
     */
    @Update("UPDATE user_virtual_assets SET quantity = GREATEST(quantity - #{count}, 0), updated_at = NOW() " +
            "WHERE user_id = #{userId} AND item_code = #{itemCode} AND quantity >= #{count}")
    int consume(@Param("userId") Long userId, @Param("itemCode") String itemCode, @Param("count") int count);
}