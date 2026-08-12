package com.heima.content.service.pins;

import com.heima.model.pins.dtos.PinsCommentDTO;
import com.heima.model.pins.dtos.PinsLinkPreviewDTO;
import com.heima.model.pins.dtos.PinsPublishDTO;
import com.heima.model.pins.dtos.PinsShareDTO;
import com.heima.model.common.dtos.ResponseResult;

public interface PinsPublicService {

    /**
     * 沸点列表
     */
    ResponseResult list(String tab, Integer page, Integer size);

    /**
     * 沸点详情
     */
    ResponseResult detail(Long pinsId);

    /**
     * 侧边栏
     */
    ResponseResult sidebar();

    /**
     * 发布沸点
     */
    ResponseResult publish(PinsPublishDTO dto);

    /**
     * 点赞
     */
    ResponseResult like(Long pinsId);

    /**
     * 取消点赞
     */
    ResponseResult unlike(Long pinsId);

    /**
     * 创建评论
     */
    ResponseResult createComment(PinsCommentDTO dto);

    /**
     * 评论列表
     */
    ResponseResult commentList(Long pinsId, Integer page, Integer size, String sort);

    /**
     * 分享
     */
    ResponseResult share(PinsShareDTO dto);

    /**
     * 链接预览
     */
    ResponseResult linkPreview(PinsLinkPreviewDTO dto);

    /**
     * 话题列表（分页+搜索）
     */
    ResponseResult topics(String keyword, Integer page, Integer size);

    /**
     * 所有圈子（按类别分组）
     */
    ResponseResult circles();

    /**
     * 上传图片
     */
    ResponseResult uploadImage(String imageUrl);
}