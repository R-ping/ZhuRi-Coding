package com.heima.content.service.draft;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.article.pojos.ApArticleDraft;
import com.heima.model.common.dtos.ResponseResult;

public interface DraftManageService extends IService<ApArticleDraft> {

    ResponseResult list(Long authorId, Integer page, Integer size, String title);

    ResponseResult deleteDraft(Long id);

    ResponseResult addDraft(ApArticleDraft draft);

    ResponseResult updateDraft(ApArticleDraft draft);
}