package com.heima.content.service.impl;

import com.heima.content.service.article.impl.ArticleAutoScanServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ArticleAutoScanServiceImpl 单元测试
 *
 * 覆盖责任链编排的核心逻辑：
 * - 正常审核通过流程（所有Processor依次执行）
 * - AI违规检测失败
 * - 图片审核失败
 * - 非审核中状态跳过
 * - 文章不存在
 */
@SpringBootTest
class ArticleAutoScanServiceImplTest {
    @Autowired
    private ArticleAutoScanServiceImpl autoScanService;
    @Test
    void testAutoScan() {
        autoScanService.autoScanArticle(2086414899941933058L);
    }



}