package com.zhuri.coding.content.service.draft;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhuri.coding.model.article.pojos.ApArticleDraft;
import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface DraftManageService extends IService<ApArticleDraft> {

    ResponseResult list(Long authorId, Integer page, Integer size, String title);

    ResponseResult deleteDraft(Long id);

    ResponseResult addDraft(ApArticleDraft draft);

    ResponseResult updateDraft(ApArticleDraft draft);
}