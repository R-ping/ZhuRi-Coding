package com.zhuri.coding.content.service.admin;

import com.zhuri.coding.model.admin.dtos.AdminPopupSaveDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 运营后台 · 弹窗公告管理（全站弹窗）。
 *
 * <p><b>与 Banner 的三点差异</b>（为什么是两个服务而不是参数化成一个）：
 * <ul>
 *   <li>时间窗<b>必填</b>：投放语义必须有截止（"永久弹窗"等于骚扰），
 *       且用户关闭记录的 Redis TTL 上限就取结束时间；</li>
 *   <li>有"当前弹窗"概念：同一时间可能有多条启用中，C 端只取用户未关闭的最新一条；</li>
 *   <li>写操作要考虑<b>与关闭记录的一致性</b>：编辑起止时间时，已关闭记录的 TTL
 *       会随之调整（见实现），删除弹窗时已关闭记录成了孤儿 —— 靠 TTL 自亡，不必清理。</li>
 * </ul>
 *
 * <p>其余骨架（默认停用、幂等闸口、条件更新、审计）与 {@link AdminBannerService} 一致。
 */
public interface AdminPopupService {

    String ACTION_CREATE = "POPUP_CREATE";
    String ACTION_UPDATE = "POPUP_UPDATE";
    String ACTION_ENABLE = "POPUP_ENABLE";
    String ACTION_DISABLE = "POPUP_DISABLE";
    String ACTION_DELETE = "POPUP_DELETE";

    String TARGET_POPUP = "POPUP";

    int MAX_PAGE_SIZE = 50;
    int DEFAULT_PAGE_SIZE = 20;

    /**
     * 弹窗列表（含停用）。
     *
     * @param status {@code 0}-停用 {@code 1}-启用；为空表示全部
     */
    ResponseResult page(String keyword, Integer status, Integer page, Integer size);

    ResponseResult detail(Long id);

    /** 新建（一律落停用态） */
    ResponseResult create(AdminPopupSaveDto dto);

    /** 编辑（按字段比对幂等；改起止时间会同步调整已关闭记录的 TTL） */
    ResponseResult update(Long id, AdminPopupSaveDto dto);

    /** 启用（停用 → 启用） */
    ResponseResult enable(Long id, String reason);

    /** 停用（启用 → 停用） */
    ResponseResult disable(Long id, String reason);

    /** 删除（<b>仅限停用态</b>） */
    ResponseResult delete(Long id, String reason);
}
