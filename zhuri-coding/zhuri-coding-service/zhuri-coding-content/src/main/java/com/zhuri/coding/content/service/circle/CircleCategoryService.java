package com.zhuri.coding.content.service.circle;

import com.zhuri.coding.model.circle.vos.CircleCategoryVO;

import java.util.List;

public interface CircleCategoryService {

    /**
     * 获取所有分类，按 sort_order 排序
     */
    List<CircleCategoryVO> listAll();

    /**
     * 根据 ID 获取分类
     */
    CircleCategoryVO getById(Long id);
}