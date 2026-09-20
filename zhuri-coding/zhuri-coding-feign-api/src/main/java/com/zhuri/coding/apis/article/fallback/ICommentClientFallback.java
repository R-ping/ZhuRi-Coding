package com.heima.apis.article.fallback;

import com.heima.apis.article.ICommentClient;
import com.heima.model.comment.dtos.CommentDto;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
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