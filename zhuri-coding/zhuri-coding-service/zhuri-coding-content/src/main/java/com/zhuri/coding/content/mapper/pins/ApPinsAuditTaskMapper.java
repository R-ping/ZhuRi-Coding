package com.heima.content.mapper.pins;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.audit.pojos.ApPinsAuditTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 沸点异步审核可靠队列 Mapper
 */
@Mapper
public interface ApPinsAuditTaskMapper extends BaseMapper<ApPinsAuditTask> {
}