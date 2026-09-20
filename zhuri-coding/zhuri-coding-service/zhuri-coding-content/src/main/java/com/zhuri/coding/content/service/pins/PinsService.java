package com.zhuri.coding.content.service.pins;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface PinsService extends IService<ApPins> {

    ResponseResult list(Long authorId, Integer page, Integer size, String status);

    ResponseResult statistics(Long authorId);

    ResponseResult createPins(ApPins pins);

    ResponseResult deletePins(Long id);
}