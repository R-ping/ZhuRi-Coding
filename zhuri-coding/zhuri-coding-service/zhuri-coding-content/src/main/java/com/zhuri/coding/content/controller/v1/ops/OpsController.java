package com.zhuri.coding.content.controller.v1.ops;

import com.zhuri.coding.content.service.ops.OpsBannerService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端 · 运营位（首页轮播 Banner + 全站弹窗）。
 *
 * <p><b>弹窗为什么没有"列表"只有 current</b>：弹窗的产品形态是"一次至多一个"，
 * 同一时间多条启用中时服务端只交出最新未关闭的一条 ——
 * 挑选逻辑（盖旧、排除已关闭）放服务端，前端拿到即渲染，
 * 不需要也不应该把"哪条弹给谁"的决策权交给每个终端各判一遍。
 *
 * <p><b>current 未登录返回空而不是 NEED_LOGIN</b>：弹窗是首页的可选信息，
 * 为它打断未登录用户的浏览毫无必要；且未登录的关闭记录没有身份可落
 * （见 {@code OpsBannerService} 注释），本来就弹不了。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    @Autowired
    private OpsBannerService opsBannerService;

    /**
     * 首页轮播 Banner（免登录）。
     * GET /api/v1/ops/banners
     */
    @GetMapping("/banners")
    public ResponseResult banners() {
        return ResponseResult.okResult(opsBannerService.listBanners());
    }

    /**
     * 当前弹窗（需登录；未登录返回空 data，不报错）。
     * GET /api/v1/ops/popup/current
     */
    @GetMapping("/popup/current")
    public ResponseResult currentPopup() {
        ApUser user = AppThreadLocalUtil.getUser();
        return ResponseResult.okResult(opsBannerService.currentPopup(user == null ? null : Long.valueOf(user.getId())));
    }

    /**
     * 关闭弹窗（幂等；弹窗不存在返回业务错误，而不是静默成功）。
     * POST /api/v1/ops/popup/{id}/close
     */
    @PostMapping("/popup/{id}/close")
    public ResponseResult closePopup(@PathVariable("id") Long id) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        boolean ok = opsBannerService.closePopup(Long.valueOf(user.getId()), id);
        if (!ok) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "弹窗不存在");
        }
        return ResponseResult.okResult();
    }
}
