package com.heima.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.notification.pojos.ImSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ImSessionMapper extends BaseMapper<ImSession> {

    List<ImSession> selectByUserId(@Param("userId") Long userId);

    ImSession selectBySessionKey(@Param("sessionKey") String sessionKey);
}