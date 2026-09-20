package com.zhuri.coding.apis.article.fallback;

import com.zhuri.coding.apis.article.ICommentClient;
import com.zhuri.coding.model.comment.dtos.CommentDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ICommentClientFallback implements ICommentClient {

    @Override
    public ResponseResult addComment(CommentDto dto) {
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "评论服务不可用");
    }

    @Override
    public ResponseResult likeComment(CommentDto dto) {
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "评论服务不可用");
    }

    @Override
    public ResponseResult getCommentById(Long id) {
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "评论服务不可用");
    }
}