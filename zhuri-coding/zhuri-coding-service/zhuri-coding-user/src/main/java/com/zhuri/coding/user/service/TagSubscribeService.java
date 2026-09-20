package com.heima.user.service;

import com.heima.model.common.dtos.ResponseResult;

public interface TagSubscribeService {
    ResponseResult discover(String sort, String keyword, Integer page, Integer size);
    ResponseResult getFollowed();
    ResponseResult follow(Integer tagId);
    ResponseResult unfollow(Integer tagId);

    /**
     * 标签详情页：按标签名查询标签详情（id、标签名、关注数、当前用户是否已关注）
     * @param tagName 标签名（URL 编码，可能含特殊字符如 C++、C#）
     * @return {id, tagName, categoryCode, categoryName, followerCount, isFollowed}
     */
    ResponseResult tagDetail(String tagName);
}