package com.zhuri.coding.apis.article;

import com.zhuri.coding.apis.article.fallback.ICommentClientFallback;
import com.zhuri.coding.model.comment.dtos.CommentDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "zhuri-coding-content", contextId = "zhuri-coding-content-commentClient", fallback = ICommentClientFallback.class)
public interface ICommentClient {

    @PostMapping("/api/v1/comment")
    public ResponseResult addComment(@RequestBody CommentDto dto);

    @PostMapping("/api/v1/comment/like")
    public ResponseResult likeComment(@RequestBody CommentDto dto);

    @GetMapping("/api/v1/comment/{id}")
    public ResponseResult getCommentById(@PathVariable("id") Long id);
}