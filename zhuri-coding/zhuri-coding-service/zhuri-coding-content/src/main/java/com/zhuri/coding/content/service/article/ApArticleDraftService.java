package com.heima.content.service.article;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.article.pojos.ApArticleDraft;
import com.heima.model.common.dtos.ResponseResult;

public interface ApArticleDraftService extends IService<ApArticleDraft> {
    ResponseResult createDraft(ApArticleDraft draft);
    ResponseResult updateDraft(ApArticleDraft draft);
    ResponseResult publishFromDraft(Long draftId);
    ResponseResult getDraftById(Long id);
    ResponseResult listDrafts(Long authorId, Integer page, Integer size);
    ResponseResult deleteDraft(Long id);
}