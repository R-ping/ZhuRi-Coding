package com.heima.reward.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.reward.entity.WelfareGoods;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface WelfareGoodsMapper extends BaseMapper<WelfareGoods> {

    /**
     * 乐观锁扣减库存：stock > 0 时执行 stock = stock - 1
     * @param goodsId 商品ID
     * @return 影响行数（0表示库存不足）
     */
    @Update("UPDATE welfare_goods SET stock = stock - 1, exchanged_count = exchanged_count + 1 WHERE id = #{goodsId} AND stock > 0")
    int updateStock(@Param("goodsId") String goodsId);
}
