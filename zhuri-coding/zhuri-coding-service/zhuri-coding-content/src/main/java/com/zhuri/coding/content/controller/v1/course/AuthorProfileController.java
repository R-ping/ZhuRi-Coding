package com.heima.content.controller.v1.course;

import com.heima.content.service.course.AuthorProfileService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.course.dtos.AuthorProfileDto;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/course/author")
@Slf4j
public class AuthorProfileController {

    @Autowired
    private AuthorProfileService authorProfileService;

    /** 获取当前作者的作者基础信息（申请回填用）；无记录返回空结构 hasProfile=false */
    @GetMapping("/profile")
    public ResponseResult getProfile() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return authorProfileService.getProfile(user.getId());
    }

    /** 保存/覆盖当前作者的作者基础信息（按 user_id upsert，允许重新填写） */
    @PostMapping("/profile")
    public ResponseResult saveProfile(@RequestBody AuthorProfileDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        log.info("保存作者基础信息, userId={}", user.getId());
        return authorProfileService.saveProfile(user.getId(), dto);
    }
}