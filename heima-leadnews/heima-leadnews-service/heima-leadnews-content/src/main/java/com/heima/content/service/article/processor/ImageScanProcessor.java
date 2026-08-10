package com.heima.content.service.article.processor;

import com.heima.common.aliyun.util.GreenImageScanPlusForOss;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleDraft.ContPic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 图片审核处理器
 * 负责调用阿里云OSS图片审核服务进行图片内容安全检测
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageScanProcessor implements ArticleAuditProcessor {

//    private final GreenImageScanPlus greenImageScan;
    private final GreenImageScanPlusForOss greenImageScanPlusForOss;
    @Override
    public boolean process(ApArticle article, String content, AuditProcessorContext context) {
        if (content == null || content.isEmpty()) {
            log.info("文章内容为空，跳过图片审核, articleId={}", article.getId());
            return true;
        }

        // 收集所有图片URL（文章内容图片 + 封面图片）
        List<ContPic> contPics = article.getContPics();
        if (contPics == null) {
            contPics = new ArrayList<>();
        }
        String coverImage = article.getCoverImage();
        if (coverImage != null && !coverImage.isEmpty()) {
            ContPic pic = new ContPic();
            pic.setPicUrl(coverImage);
            contPics.add(pic);
        }

        if (contPics.isEmpty()) {
            log.info("文章无图片，跳过图片审核, articleId={}", article.getId());
            return true;
        }

        log.info("开始图片审核, articleId={}, imageCount={}", article.getId(), contPics.size());
        try {
            for (ContPic contPic : contPics) {
                Map map = greenImageScanPlusForOss.imageScan(contPic.getPicUrl());
                if (map != null) {
                    String level = (String) map.get("level");
                    if ("high".equals(level)) {
                        log.info("图片审核high级别, articleId={}, pic={}", article.getId(), contPic.getPicUrl());
                        context.putExtra("failReason", "当前文章中的图片存在违规内容");
                        return false;
                    } else if ("medium".equals(level)) {
                        log.info("图片审核medium级别, articleId={}, pic={}", article.getId(), contPic.getPicUrl());
                        context.putExtra("failReason", "当前文章中的图片存在不确定内容");
                        return false;
                    }
                    log.info("图片审核通过, articleId={}", article.getId());
                    return true;
                }
                log.info("图片审核发生异常时，降级为通过, articleId={}, pic={}", article.getId(), contPic.getPicUrl());
            }
        } catch (Exception e) {
            log.error("图片审核异常, articleId={}", article.getId(), e);
            context.putExtra("failReason", "图片审核异常");
            return false;
        }
        return true;
    }
}