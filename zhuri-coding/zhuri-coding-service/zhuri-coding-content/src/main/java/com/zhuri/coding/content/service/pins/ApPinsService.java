package com.heima.content.service.pins;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.common.dtos.ResponseResult;

import java.util.Map;

public interface ApPinsService extends IService<ApPins> {

    ResponseResult findList(Integer page, Integer size, Byte status);

    ResponseResult deleteById(Long id);

    ResponseResult updateStatus(Long id, Byte status, String reason);
}