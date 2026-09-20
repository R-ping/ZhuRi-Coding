package com.heima.content.controller.v1.order;

import com.heima.content.service.order.DiscountService;
import com.heima.model.course.dtos.CourseDiscountDto;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/course/discount")
public class DiscountController {

    @Autowired
    private DiscountService discountService;

    /** 创建折扣码 */
    @PostMapping("/create")
    public ResponseResult createDiscount(@RequestBody CourseDiscountDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return discountService.createDiscount(dto, user.getId().longValue());
    }

    /** 折扣码列表 */
    @GetMapping("/list")
    public ResponseResult listDiscounts(@RequestParam Long courseId) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return discountService.listDiscounts(courseId, user.getId().longValue());
    }

    /** 停用折扣码 */
    @PostMapping("/disable")
    public ResponseResult disableDiscount(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        Long discountId = params.get("discountId") != null ? Long.parseLong(params.get("discountId").toString()) : null;
        return discountService.disableDiscount(discountId, user.getId().longValue());
    }

    /** 校验折扣码（公开接口，用于下单前预览） */
    @GetMapping("/validate")
    public ResponseResult validateDiscount(@RequestParam String code, @RequestParam Long courseId) {
        return discountService.validateDiscountForPreview(code, courseId);
    }
}