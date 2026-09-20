package com.heima.user.service;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dto.BlockDTO;

public interface BlockService {
    ResponseResult getBlocks(Integer type, Integer page, Integer size);
    ResponseResult addBlock(BlockDTO dto);
    ResponseResult removeBlock(Long id);
}