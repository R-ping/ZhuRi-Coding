package com.heima.content.controller.v1.pins;

import com.heima.content.config.EditorConfig;
import com.heima.content.service.pins.ApPinsService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pins/admin")
@Slf4j
public class PinsController {

    @Autowired
    private ApPinsService apPinsService;

    /** 校验当前用户是否为运营编辑（仅白名单编辑账号可执行管理操作，防止垂直越权） */
    private ResponseResult checkEditor() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (!EditorConfig.isEditor(user.getId())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无管理员权限");
        }
        return null;
    }

    @GetMapping("/list")
    public ResponseResult findList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Byte status) {
        ResponseResult auth = checkEditor();
        if (auth != null) {
            return auth;
        }
        return apPinsService.findList(page, size, status);
    }

    @DeleteMapping("/{id}")
    public ResponseResult deleteById(@PathVariable Long id) {
        ResponseResult auth = checkEditor();
        if (auth != null) {
            return auth;
        }
        return apPinsService.deleteById(id);
    }

    @PutMapping("/status")
    public ResponseResult updateStatus(@RequestBody Map<String, Object> params) {
        ResponseResult auth = checkEditor();
        if (auth != null) {
            return auth;
        }
        Long id = Long.parseLong(params.get("id").toString());
        Byte status = Byte.parseByte(params.get("status").toString());
        String reason = params.get("reason") != null ? params.get("reason").toString() : null;
        return apPinsService.updateStatus(id, status, reason);
    }
}