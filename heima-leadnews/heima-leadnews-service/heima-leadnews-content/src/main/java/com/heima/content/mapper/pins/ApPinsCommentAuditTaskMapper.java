package com.heima.content.mapper.pins;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.audit.pojos.ApPinsCommentAuditTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 沸点评论异步审核可靠队列 Mapper
 */
@Mapper
public interface ApPinsCommentAuditTaskMapper extends BaseMapper<ApPinsCommentAuditTask> {
}
