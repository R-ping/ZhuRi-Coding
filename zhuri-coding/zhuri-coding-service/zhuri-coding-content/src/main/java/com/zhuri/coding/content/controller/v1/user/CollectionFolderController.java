package com.zhuri.coding.content.controller.v1.user;

import com.zhuri.coding.content.service.collection.CollectionFolderService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收藏夹管理接口（F4）
 *
 * <p>收藏夹属于当前登录用户的私密组织结构：所有操作以 ThreadLocal 中的用户为准，
 * 不接受外部传入 userId（防越权/伪造）。</p>
 *
 * <p>路径：/api/v1/user/collection/folders（经网关 /content 前缀转发）</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user/collection/folders")
public class CollectionFolderController {

    @Autowired
    private CollectionFolderService collectionFolderService;

    /**
     * 我的收藏夹列表（含条目数与最近使用时间，供收藏页筛选与收藏选择面板）
     * GET /api/v1/user/collection/folders
     */
    @GetMapping
    public ResponseResult list() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return collectionFolderService.listFolders(user.getId());
    }

    /**
     * 新建收藏夹
     * POST /api/v1/user/collection/folders {"name": "面试八股"}
     */
    @PostMapping
    public ResponseResult create(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        String name = params != null && params.get("name") != null ? params.get("name").toString() : null;
        return collectionFolderService.createFolder(user.getId(), name);
    }

    /**
     * 重命名收藏夹
     * PUT /api/v1/user/collection/folders/{folderId} {"name": "新名称"}
     */
    @PutMapping("/{folderId}")
    public ResponseResult rename(@PathVariable Long folderId, @RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        String name = params != null && params.get("name") != null ? params.get("name").toString() : null;
        return collectionFolderService.renameFolder(user.getId(), folderId, name);
    }

    /**
     * 删除收藏夹（其中收藏回退默认收藏夹，收藏记录不删除）
     * DELETE /api/v1/user/collection/folders/{folderId}
     */
    @DeleteMapping("/{folderId}")
    public ResponseResult remove(@PathVariable Long folderId) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return collectionFolderService.removeFolder(user.getId(), folderId);
    }

    /**
     * 收藏夹排序（与相邻收藏夹交换，已在首/末位时为 no-op）
     * PUT /api/v1/user/collection/folders/{folderId}/move?direction=up|down
     */
    @PutMapping("/{folderId}/move")
    public ResponseResult move(@PathVariable Long folderId, @RequestParam String direction) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return collectionFolderService.moveFolder(user.getId(), folderId, direction);
    }
}