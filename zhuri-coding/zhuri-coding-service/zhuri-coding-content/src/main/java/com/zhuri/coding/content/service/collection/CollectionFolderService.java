package com.zhuri.coding.content.service.collection;

import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 收藏夹管理（F4）
 *
 * <p>收藏夹归属当前登录用户：创建/重命名/删除/排序均以 ThreadLocal 中的用户为准。
 * 规则（PRD F4）：名称 1-20 字、同用户不可重名、单用户上限 50 个；
 * 删除收藏夹时其中的收藏回退"默认收藏夹"（folder_id 置空），收藏记录不删除。</p>
 */
public interface CollectionFolderService {

    /** 我的收藏夹列表（含条目数与最近使用时间，供收藏页筛选与收藏选择面板） */
    ResponseResult listFolders(Integer userId);

    /** 新建收藏夹（名称 1-20 字、不可重名、数量上限 50） */
    ResponseResult createFolder(Integer userId, String name);

    /** 重命名收藏夹 */
    ResponseResult renameFolder(Integer userId, Long folderId, String name);

    /** 删除收藏夹（其中收藏回退默认收藏夹） */
    ResponseResult removeFolder(Integer userId, Long folderId);

    /**
     * 收藏夹排序（与相邻收藏夹交换，direction=up|down）。
     * 已在首/末位时为 no-op（不报错），前端可安全地重复点击。
     */
    ResponseResult moveFolder(Integer userId, Long folderId, String direction);

    /**
     * 校验收藏夹归属：收藏动作落库前调用。
     *
     * @return 收藏夹存在且属于该用户时返回原 folderId；否则返回 null（调用方回退默认收藏夹）
     */
    Long resolveValidFolderId(Integer userId, Long folderId);
}