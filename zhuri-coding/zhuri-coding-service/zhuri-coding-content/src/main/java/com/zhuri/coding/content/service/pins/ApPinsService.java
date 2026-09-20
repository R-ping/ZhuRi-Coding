package com.zhuri.coding.content.service.pins;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.common.dtos.ResponseResult;

import java.util.Map;

public interface ApPinsService extends IService<ApPins> {

    ResponseResult findList(Integer page, Integer size, Byte status);

    ResponseResult deleteById(Long id);

    ResponseResult updateStatus(Long id, Byte status, String reason);
}