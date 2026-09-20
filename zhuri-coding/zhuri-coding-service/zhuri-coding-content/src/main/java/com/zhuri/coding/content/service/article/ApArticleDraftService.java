package com.zhuri.coding.content.service.article;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhuri.coding.model.article.pojos.ApArticleDraft;
import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface ApArticleDraftService extends IService<ApArticleDraft> {
    ResponseResult createDraft(ApArticleDraft draft);
    ResponseResult updateDraft(ApArticleDraft draft);
    ResponseResult publishFromDraft(Long draftId);
    ResponseResult getDraftById(Long id);
    ResponseResult listDrafts(Long authorId, Integer page, Integer size);
    ResponseResult deleteDraft(Long id);
}