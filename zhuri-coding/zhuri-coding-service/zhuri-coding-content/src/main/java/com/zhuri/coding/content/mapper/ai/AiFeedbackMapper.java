package com.zhuri.coding.content.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.ai.pojos.AiFeedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 反馈 Mapper
 */
@Mapper
public interface AiFeedbackMapper extends BaseMapper<AiFeedback> {
}
