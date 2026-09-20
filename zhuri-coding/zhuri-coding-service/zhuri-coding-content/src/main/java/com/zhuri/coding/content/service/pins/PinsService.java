package com.heima.content.service.pins;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.common.dtos.ResponseResult;

public interface PinsService extends IService<ApPins> {

    ResponseResult list(Long authorId, Integer page, Integer size, String status);

    ResponseResult statistics(Long authorId);

    ResponseResult createPins(ApPins pins);

    ResponseResult deletePins(Long id);
}