package com.zhuri.coding.content.service.activity;

import com.zhuri.coding.model.activity.pojos.ApActivity;

import java.util.Map;

public interface ActivityService {

    /**
     * 分页查询活动列表
     * @param page 页码
     * @param size 每页条数
     * @param type 活动类型
     * @param status 活动状态
     * @param category 活动分类
     * @return Map包含list、total、page、size
     */
    Map<String, Object> list(int page, int size, String type, String status, String category);

    /**
     * 根据ID查询活动
     * @param id 活动ID
     * @return ApActivity
     */
    ApActivity getById(Long id);
}