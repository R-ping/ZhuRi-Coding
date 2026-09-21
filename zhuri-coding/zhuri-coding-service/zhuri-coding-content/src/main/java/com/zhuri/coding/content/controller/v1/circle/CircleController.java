
package com.zhuri.coding.content.controller.v1.circle;

import com.zhuri.coding.content.service.circle.CircleService;
import com.zhuri.coding.model.circle.vos.CircleVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
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
        // 详情为公开只读接口（网关白名单内），未登录按匿名浏览处理，避免 getUser() 空指针
        ApUser current = AppThreadLocalUtil.getUser();
        Integer userId = current == null ? null : current.getId();
        CircleVO vo = circleService.detail(id, userId);
        if (vo == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        return ResponseResult.okResult(vo);
    }

    @PostMapping("/{id}/join")
    public ResponseResult join(@PathVariable Long id) {
        // 加入圈子属写操作：网关已要求登录，此处再做一次兜底（防绕过网关直连服务）
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        circleService.join(id, user.getId());
        return ResponseResult.okResult();
    }

    @PostMapping("/{id}/leave")
    public ResponseResult leave(@PathVariable Long id) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        circleService.leave(id, user.getId());
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
        // 该路径在网关公开白名单内（便于未登录时前端直接调用），故此处必须容忍匿名，返回"需登录"而非 500
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return ResponseResult.okResult(circleService.myCircles(user.getId()));
    }

    @GetMapping("/categories/{categoryId}/circles")
    public ResponseResult listByCategory(@PathVariable Long categoryId,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return ResponseResult.okResult(circleService.listByCategory(categoryId, page, size));
    }
}
