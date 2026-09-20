package com.zhuri.coding.content.controller.v1.article;

import com.zhuri.coding.content.service.article.ApArticleDraftService;
import com.zhuri.coding.model.article.pojos.ApArticleDraft;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/draft")
public class ApArticleDraftController {

    @Autowired
    private ApArticleDraftService apArticleDraftService;

    @PostMapping("/create")
    public ResponseResult createDraft(@RequestBody ApArticleDraft draft) {
        return apArticleDraftService.createDraft(draft);
    }

    @PutMapping("/update")
    public ResponseResult updateDraft(@RequestBody ApArticleDraft draft) {
        return apArticleDraftService.updateDraft(draft);
    }

    @PostMapping("/publish")
    public ResponseResult publishFromDraft(@RequestBody Map<String, Long> body) {
        Long draftId = body.get("draftId");
        return apArticleDraftService.publishFromDraft(draftId);
    }

    @GetMapping("/{id}")
    public ResponseResult getDraftById(@PathVariable Long id) {
        return apArticleDraftService.getDraftById(id);
    }

    @GetMapping("/list")
    public ResponseResult listDrafts(
            @RequestParam(required = false) Long authorId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return apArticleDraftService.listDrafts(authorId, page, size);
    }

    @GetMapping("/manage/detail")
    public ResponseResult getDraftByQuery(@RequestParam Long id) {
        return apArticleDraftService.getDraftById(id);
    }

    @DeleteMapping("/{id}")
    public ResponseResult deleteDraft(@PathVariable Long id) {
        return apArticleDraftService.deleteDraft(id);
    }
}