package com.zhuri.coding.content.controller.v1.circle;

import com.zhuri.coding.content.service.circle.CircleCategoryService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.circle.vos.CircleCategoryVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/circle/categories")
public class CircleCategoryController {

    @Autowired
    private CircleCategoryService circleCategoryService;

    @GetMapping
    public ResponseResult listAll() {
        return ResponseResult.okResult(circleCategoryService.listAll());
    }

    @GetMapping("/{id}")
    public ResponseResult getById(@PathVariable Long id) {
        CircleCategoryVO vo = circleCategoryService.getById(id);
        if (vo == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        return ResponseResult.okResult(vo);
    }
}