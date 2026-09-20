package com.zhuri.coding.content.mapper.comment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.comment.pojos.ApComment;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApCommentMapper extends BaseMapper<ApComment> {
}