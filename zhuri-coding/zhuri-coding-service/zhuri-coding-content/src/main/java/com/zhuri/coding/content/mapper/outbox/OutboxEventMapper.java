package com.zhuri.coding.content.mapper.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.outbox.pojos.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;

/** 本地消息表 Mapper（Outbox） */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
}
