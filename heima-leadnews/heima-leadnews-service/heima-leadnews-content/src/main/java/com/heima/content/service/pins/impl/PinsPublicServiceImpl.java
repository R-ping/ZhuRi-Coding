package com.heima.content.service.pins.impl;

import com.heima.content.service.pins.PinsPublicService;
import com.heima.model.pins.dtos.PinsCommentDTO;
import com.heima.model.pins.dtos.PinsLinkPreviewDTO;
import com.heima.model.pins.dtos.PinsPublishDTO;
import com.heima.model.pins.dtos.PinsShareDTO;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class PinsPublicServiceImpl implements PinsPublicService {

    @Autowired
    private PinsQueryService pinsQueryService;

    @Autowired
    private PinsPublishService pinsPublishService;

    @Autowired
    private PinsInteractionService pinsInteractionService;

    @Override
    public ResponseResult list(String tab, Integer page, Integer size) {
        return pinsQueryService.list(tab, page, size);
    }

    @Override
    public ResponseResult detail(Long pinsId) {
        return pinsQueryService.detail(pinsId);
    }

    @Override
    public ResponseResult sidebar() {
        return pinsQueryService.sidebar();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult publish(PinsPublishDTO dto) {
        return pinsPublishService.publish(dto);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult like(Long pinsId) {
        return pinsInteractionService.like(pinsId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult unlike(Long pinsId) {
        return pinsInteractionService.unlike(pinsId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createComment(PinsCommentDTO dto) {
        return pinsInteractionService.createComment(dto);
    }

    @Override
    public ResponseResult commentList(Long pinsId, Integer page, Integer size, String sort) {
        return pinsQueryService.commentList(pinsId, page, size, sort);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult share(PinsShareDTO dto) {
        return pinsInteractionService.share(dto);
    }

    @Override
    public ResponseResult linkPreview(PinsLinkPreviewDTO dto) {
        return pinsQueryService.linkPreview(dto);
    }

    @Override
    public ResponseResult topics(String keyword, Integer page, Integer size) {
        return pinsQueryService.topics(keyword, page, size);
    }

    @Override
    public ResponseResult circles() {
        return pinsQueryService.circles();
    }

    @Override
    public ResponseResult uploadImage(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "图片URL不能为空");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("url", imageUrl);
        return ResponseResult.okResult(data);
    }
}