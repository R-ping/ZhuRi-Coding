package com.zhuri.coding.user.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.dto.BlockDTO;

public interface BlockService {
    ResponseResult getBlocks(Integer type, Integer page, Integer size);
    ResponseResult addBlock(BlockDTO dto);
    ResponseResult removeBlock(Long id);

    /**
     * 判断两人之间是否存在任一方向的拉黑（用户维度）。
     * <p>拉黑数据只存在于用户库，其他服务无法自行判断，必须回这里确认。
     */
    boolean isBlockedBothWays(Long userId, Long targetId);
}