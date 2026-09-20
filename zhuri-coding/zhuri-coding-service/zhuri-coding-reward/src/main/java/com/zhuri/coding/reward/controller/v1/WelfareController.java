package com.zhuri.coding.reward.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import com.zhuri.coding.reward.service.WelfareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/welfare")
public class WelfareController {

    @Autowired
    private WelfareService welfareService;

    /**
     * 从可信线程取当前登录用户（拦截器依据 accToken 解析），
     * 废弃原"匿名缺省为 1L"的逻辑，防止未登录冒充用户兑换/查看兑换记录。
     */
    private ResponseResult requireUserId(java.util.function.Function<Long, ResponseResult> action) {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return action.apply(AppThreadLocalUtil.getUser().getId().longValue());
    }

    /** 获取福利商品列表（公开） */
    @GetMapping("/goods")
    public ResponseResult goodsList(@RequestParam(defaultValue = "1") Integer type,
                                     @RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "20") Integer size) {
        return welfareService.getGoodsList(type, page, size);
    }

    /** 获取商品详情（公开） */
    @GetMapping("/goods/{goodsId}")
    public ResponseResult goodsDetail(@PathVariable String goodsId) {
        return welfareService.getGoodsDetail(goodsId);
    }

    /** 执行兑换 */
    @PostMapping("/exchange")
    public ResponseResult exchange(@RequestBody Map<String, Object> body) {
        return requireUserId(userId -> welfareService.exchange(userId, body));
    }

    /** 获取我的兑换记录 */
    @GetMapping("/my-exchanges")
    public ResponseResult myExchanges(@RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "20") Integer size,
                                       @RequestParam(defaultValue = "all") String status) {
        return requireUserId(userId -> welfareService.getMyExchanges(userId, page, size, status));
    }
}
