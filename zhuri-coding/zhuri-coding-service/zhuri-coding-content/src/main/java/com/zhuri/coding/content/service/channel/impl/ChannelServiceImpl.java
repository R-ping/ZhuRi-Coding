package com.zhuri.coding.content.service.channel.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.content.mapper.channel.ChannelMapper;
import com.zhuri.coding.content.service.channel.ChannelService;
import com.zhuri.coding.model.channel.pojos.ApChannel;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class ChannelServiceImpl extends ServiceImpl<ChannelMapper, ApChannel> implements ChannelService {

    /**
     * 查询所有频道
     * @return
     */
    @Override
    public ResponseResult findAll() {
        return ResponseResult.okResult(list());
    }
}