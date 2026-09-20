
package com.heima.content.controller.v1.circle;

import com.heima.content.service.circle.CircleService;
import com.heima.model.circle.vos.CircleVO;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/circle")
public class CircleController {

    @Autowired
    private CircleService circleService;

    @GetMapping("/recommend")
    public ResponseResult recommend() {
        return ResponseResult.okResult(circleService.recommend());
    }

    @GetMapping("/square")
    public ResponseResult square(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return ResponseResult.okResult(circleService.square(page, size));
    }

    @GetMapping("/hot")
    public ResponseResult hot() {
        return ResponseResult.okResult(circleService.hot());
    }

    @GetMapping("/{id}")
    public ResponseResult detail(@PathVariable Long id) {
        Integer userId = null;
        try {
            userId = AppThreadLocalUtil.getUser().getId();
        } catch (Exception e) {
        }
        CircleVO vo = circleService.detail(id, userId);
        if (vo == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        return ResponseResult.okResult(vo);
    }

    @PostMapping("/{id}/join")
    public ResponseResult join(@PathVariable Long id) {
        Integer userId = AppThreadLocalUtil.getUser().getId();
        circleService.join(id, userId);
        return ResponseResult.okResult();
    }

    @PostMapping("/{id}/leave")
    public ResponseResult leave(@PathVariable Long id) {
        Integer userId = AppThreadLocalUtil.getUser().getId();
        circleService.leave(id, userId);
        return ResponseResult.okResult();
    }

    @GetMapping("/{id}/feed")
    public ResponseResult feed(@PathVariable Long id,
                               @RequestParam(defaultValue = "hot") String tab,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return ResponseResult.okResult(circleService.feed(id, tab, page, size));
    }

    @GetMapping("/my")
    public ResponseResult myCircles() {
        Integer userId = AppThreadLocalUtil.getUser().getId();
        return ResponseResult.okResult(circleService.myCircles(userId));
    }

    @GetMapping("/categories/{categoryId}/circles")
    public ResponseResult listByCategory(@PathVariable Long categoryId,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return ResponseResult.okResult(circleService.listByCategory(categoryId, page, size));
    }
}
