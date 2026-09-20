package com.heima.content.mapper.aigc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.aigc.pojos.AigcRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * AIGC 水文检测记录 Mapper
 */
@Mapper
public interface AigcRecordMapper extends BaseMapper<AigcRecord> {
}
