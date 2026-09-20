package com.zhuri.coding.content.mapper.aigc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.aigc.pojos.AigcRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * AIGC 水文检测记录 Mapper
 */
@Mapper
public interface AigcRecordMapper extends BaseMapper<AigcRecord> {
}
