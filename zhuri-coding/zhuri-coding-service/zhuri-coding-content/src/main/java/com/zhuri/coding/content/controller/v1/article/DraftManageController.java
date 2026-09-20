package com.zhuri.coding.content.controller.v1.article;

import com.zhuri.coding.content.service.draft.DraftManageService;
import com.zhuri.coding.model.article.pojos.ApArticleDraft;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/draft/manage")
public class DraftManageController {

    @Autowired
    private DraftManageService draftManageService;

    @GetMapping("/list")
    public ResponseResult list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String title) {
        return draftManageService.list(null, page, size, title);
    }

    @PostMapping("/delete")
    public ResponseResult delete(@RequestBody Map<String, Long> body) {
        return draftManageService.deleteDraft(body.get("id"));
    }

    @GetMapping("/count")
    public ResponseResult count() {
        return draftManageService.list(null, 1, 1, null);
    }
}