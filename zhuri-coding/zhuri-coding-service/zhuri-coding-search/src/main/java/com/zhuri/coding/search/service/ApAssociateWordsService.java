package com.zhuri.coding.search.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.UserSearchDto;

public interface ApAssociateWordsService {

    /**
     * 搜索联想词
     * @param dto
     * @return
     */
    public ResponseResult search(UserSearchDto dto);

    /**
     * 增加搜索次数
     * @param keyword
     */
    void incrementSearchCount(String keyword);
}
