package com.zhuri.coding.content.service.admin;

import com.zhuri.coding.model.admin.dtos.AdminBannerSaveDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 运营后台 · Banner 管理（首页轮播）。
 *
 * <p><b>为什么是"建档型"而不是运营位配置那种"清单替换型"</b>：人气圈子/推荐话题
 * 的本质是"从候选池里挑几条摆个序"，一条记录 = 一个引用；而 Banner 每条都是
 * 凭空新建的素材（图、链接、时间窗都要人来定），走 {@code AdminActivityController}
 * 那套「建档 → 编辑 → 启停 → 删除」的 CRUD 更合适。两类形态不同，所以不塞进
 * {@code AdminOpsConfigController}，各建控制器（权限同属 {@code OPS_CONFIG}）。
 *
 * <p><b>与活动 CMS 相同的骨架</b>（实现细节见 {@code AdminBannerServiceImpl}）：
 * 新建一律先落停用态（启用是独立动作、独立理由）；启停删按状态比对、
 * 编辑按字段比对做幂等闸口，明确报错不静默成功；状态类动作走"先读后条件更新"。
 *
 * <p><b>排序为什么是数字列而不是拖拽端点</b>：轮播顺序由 {@code sort_order} 承载，
 * 编辑时改数字即可达成任意排列。单独的"上移/下移/拖拽"端点只是同一件事的
 * 交互糖，第一期不值得为它多养一组接口。
 */
public interface AdminBannerService {

    String ACTION_CREATE = "BANNER_CREATE";
    String ACTION_UPDATE = "BANNER_UPDATE";
    String ACTION_ENABLE = "BANNER_ENABLE";
    String ACTION_DISABLE = "BANNER_DISABLE";
    String ACTION_DELETE = "BANNER_DELETE";

    String TARGET_BANNER = "BANNER";

    /** 列表单页上限 */
    int MAX_PAGE_SIZE = 50;
    /** 列表默认每页条数 */
    int DEFAULT_PAGE_SIZE = 20;
    /** 展示顺序上下限（三位数足够表达"哪条在前"，拦住手滑输入 99999） */
    int SORT_ORDER_MAX = 999;

    /**
     * Banner 列表（含停用）。
     *
     * @param status {@code 0}-停用 {@code 1}-启用；为空表示全部，非法取值报参数错误
     */
    ResponseResult page(String keyword, Integer status, Integer page, Integer size);

    ResponseResult detail(Long id);

    /** 新建（一律落停用态） */
    ResponseResult create(AdminBannerSaveDto dto);

    /** 编辑（按字段比对幂等） */
    ResponseResult update(Long id, AdminBannerSaveDto dto);

    /** 启用（停用 → 启用；启用中重复启用明确报错） */
    ResponseResult enable(Long id, String reason);

    /** 停用（启用 → 停用） */
    ResponseResult disable(Long id, String reason);

    /**
     * 删除（<b>仅限停用态</b>）。启用中的 Banner 还挂在用户眼前，
     * 删除它 = "从历史里抹去"，请先停用再做删除决策。
     */
    ResponseResult delete(Long id, String reason);
}
