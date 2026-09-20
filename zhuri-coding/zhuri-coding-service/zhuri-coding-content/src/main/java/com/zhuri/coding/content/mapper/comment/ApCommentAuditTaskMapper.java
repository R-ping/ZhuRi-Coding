package com.zhuri.coding.content.mapper.comment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.audit.pojos.ApCommentAuditTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评论异步审核可靠队列 Mapper
 */
@Mapper
public interface ApCommentAuditTaskMapper extends BaseMapper<ApCommentAuditTask> {
}