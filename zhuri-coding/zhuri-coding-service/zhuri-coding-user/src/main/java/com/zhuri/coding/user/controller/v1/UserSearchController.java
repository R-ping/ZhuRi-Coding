package com.zhuri.coding.user.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.UserSearchDto;
import com.zhuri.coding.user.service.ApUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户库搜索接口
 *
 * <p>路径与前端 conf 中 user_search 的 url 映射一致（sv=user，网关前缀 /user），公开只读。</p>
 */
@RestController
@RequestMapping("/api/v1/search")
public class UserSearchController {

    @Autowired
    private ApUserService apUserService;

    /** 用户搜索：按昵称 LIKE 分页查询正常状态的用户，公开只读 */
    @PostMapping("/user")
    public ResponseResult search(@RequestBody UserSearchDto dto) {
        return apUserService.searchUser(dto.getSearchWords(), dto.getPageNum(), dto.getPageSize());
    }
}