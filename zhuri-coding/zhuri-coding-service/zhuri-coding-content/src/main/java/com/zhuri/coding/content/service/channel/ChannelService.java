package com.heima.content.service.channel;

import com.heima.model.common.dtos.ResponseResult;

public interface ChannelService {

    /**
     * 查询所有频道
     * @return
     */
    ResponseResult findAll();
}