package com.heima.content.service.comment.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.content.mapper.comment.ApArticleCommentMapper;
import com.heima.content.service.comment.ApArticleCommentService;
import com.heima.model.comment.pojos.ApArticleComment;
import org.springframework.stereotype.Service;

@Service
public class ApArticleCommentServiceImpl extends ServiceImpl<ApArticleCommentMapper, ApArticleComment> implements ApArticleCommentService {
}