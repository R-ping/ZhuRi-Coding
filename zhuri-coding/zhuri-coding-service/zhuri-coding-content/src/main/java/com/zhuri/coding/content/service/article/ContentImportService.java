package com.zhuri.coding.content.service.article;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import org.springframework.web.multipart.MultipartFile;

public interface ContentImportService {

    /**
     * 导入文件生成文章草稿
     * 支持 Word、PDF、TXT、Markdown 等格式，通过 Apache Tika 自动检测并解析
     *
     * @param file 上传的文件
     * @return 导入结果，包含草稿信息
     */
    ResponseResult importArticle(MultipartFile file);
}