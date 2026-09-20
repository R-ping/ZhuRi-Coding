package com.zhuri.coding.content.service.comment.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.content.mapper.comment.ApArticleCommentMapper;
import com.zhuri.coding.content.service.comment.ApArticleCommentService;
import com.zhuri.coding.model.comment.pojos.ApArticleComment;
import org.springframework.stereotype.Service;

@Service
public class ApArticleCommentServiceImpl extends ServiceImpl<ApArticleCommentMapper, ApArticleComment> implements ApArticleCommentService {
}