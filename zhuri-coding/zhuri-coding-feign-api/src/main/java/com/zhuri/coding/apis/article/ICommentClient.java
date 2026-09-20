package com.heima.apis.article;

import com.heima.apis.article.fallback.ICommentClientFallback;
import com.heima.model.comment.dtos.CommentDto;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "leadnews-content", contextId = "leadnews-content-commentClient", fallback = ICommentClientFallback.class)
public interface ICommentClient {

    @PostMapping("/api/v1/comment")
    public ResponseResult addComment(@RequestBody CommentDto dto);

    @PostMapping("/api/v1/comment/like")
    public ResponseResult likeComment(@RequestBody CommentDto dto);

    @GetMapping("/api/v1/comment/{id}")
    public ResponseResult getCommentById(@PathVariable("id") Long id);
}