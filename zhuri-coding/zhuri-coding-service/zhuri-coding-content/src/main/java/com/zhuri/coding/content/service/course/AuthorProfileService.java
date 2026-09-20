package com.zhuri.coding.content.service.course;

import com.zhuri.coding.model.course.dtos.AuthorProfileDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 作者基础信息服务：小册申请时用于保存与回填作者个人基础信息。
 */
public interface AuthorProfileService {

    /** 获取当前作者基础信息；无记录时返回各字段为空的默认结构（含 hasProfile=false） */
    ResponseResult getProfile(Integer userId);

    /** 保存/覆盖当前作者基础信息（按 user_id 唯一键 upsert） */
    ResponseResult saveProfile(Integer userId, AuthorProfileDto dto);
}