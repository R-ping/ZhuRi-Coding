package com.zhuri.coding.content.mapper.topic;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.topic.pojos.ApTopic;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TopicMapper extends BaseMapper<ApTopic> {
}