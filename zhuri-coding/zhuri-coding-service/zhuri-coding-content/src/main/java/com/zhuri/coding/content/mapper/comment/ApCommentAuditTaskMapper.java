package com.heima.content.mapper.comment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.audit.pojos.ApCommentAuditTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评论异步审核可靠队列 Mapper
 */
@Mapper
public interface ApCommentAuditTaskMapper extends BaseMapper<ApCommentAuditTask> {
}