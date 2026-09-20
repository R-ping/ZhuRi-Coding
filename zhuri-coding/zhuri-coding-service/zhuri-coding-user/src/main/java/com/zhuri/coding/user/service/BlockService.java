package com.zhuri.coding.user.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.dto.BlockDTO;

public interface BlockService {
    ResponseResult getBlocks(Integer type, Integer page, Integer size);
    ResponseResult addBlock(BlockDTO dto);
    ResponseResult removeBlock(Long id);
}