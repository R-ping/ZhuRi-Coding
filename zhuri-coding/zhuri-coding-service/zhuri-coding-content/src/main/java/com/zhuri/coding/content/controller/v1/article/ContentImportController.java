package com.heima.content.controller.v1.article;

import com.heima.content.service.article.ContentImportService;
import com.heima.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/v1/article")
public class ContentImportController {

    @Autowired
    private ContentImportService contentImportService;

    @PostMapping("/import")
    public ResponseResult importArticle(@RequestParam("file") MultipartFile file) {
        return contentImportService.importArticle(file);
    }
}