package com.zhuri.coding.content.service.coding;

import com.zhuri.coding.model.coding.dtos.CodingProfileSettingDTO;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 能力档案服务（Coding 延展第二层）
 *
 * <p>档案聚合自第一层答题流水与既有社区数据（文章/被收藏/签到），并叠加隐私开关；
 * 本人视角全量返回，访客视角按开关逐块裁剪。</p>
 */
public interface CodingProfileService {

    /**
     * 能力档案（本人/访客共用）
     *
     * @param targetUserId 档案所有者用户ID
     * @param viewerUserId 当前登录用户ID（可为 null，表示匿名访客）
     */
    ResponseResult profile(Integer targetUserId, Integer viewerUserId);

    /**
     * 读取隐私开关（无记录返回默认值：整体私有、分项公开）
     */
    ResponseResult getSetting(Integer userId);

    /**
     * 保存隐私开关（字段为空表示不修改；首次保存自动建行）
     */
    ResponseResult updateSetting(Integer userId, CodingProfileSettingDTO dto);
}