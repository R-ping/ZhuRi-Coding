package com.zhuri.coding.content.service.channel;

import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface ChannelService {

    /**
     * 查询所有频道
     * @return
     */
    ResponseResult findAll();
}