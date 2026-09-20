package com.heima.content.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.ai.pojos.AiFeedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 反馈 Mapper
 */
@Mapper
public interface AiFeedbackMapper extends BaseMapper<AiFeedback> {
}
