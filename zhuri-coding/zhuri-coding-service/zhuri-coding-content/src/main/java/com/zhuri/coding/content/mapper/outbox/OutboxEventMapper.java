package com.heima.content.mapper.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.outbox.pojos.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;

/** 本地消息表 Mapper（Outbox） */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
}
