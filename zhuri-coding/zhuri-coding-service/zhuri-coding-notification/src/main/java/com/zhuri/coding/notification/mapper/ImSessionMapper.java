package com.zhuri.coding.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.notification.pojos.ImSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ImSessionMapper extends BaseMapper<ImSession> {

    List<ImSession> selectByUserId(@Param("userId") Long userId);

    ImSession selectBySessionKey(@Param("sessionKey") String sessionKey);
}