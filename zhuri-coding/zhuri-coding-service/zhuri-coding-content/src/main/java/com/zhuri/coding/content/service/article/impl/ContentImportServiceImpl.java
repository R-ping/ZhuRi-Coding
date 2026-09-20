package com.zhuri.coding.content.service.article.impl;

import com.zhuri.coding.content.service.article.ApArticleDraftService;
import com.zhuri.coding.content.service.article.ContentImportService;
import com.zhuri.coding.model.article.pojos.ApArticleDraft;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

@Slf4j
@Service
public class ContentImportServiceImpl implements ContentImportService {

    @Autowired
    private ApArticleDraftService apArticleDraftService;

    @Override
    public ResponseResult importArticle(MultipartFile file) {
        // 1. 校验登录
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        // 2. 校验文件
        if (file == null || file.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文件名不能为空");
        }

        long fileSize = file.getSize();
        if (fileSize > 50 * 1024 * 1024) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文件大小不能超过50MB");
        }

        log.info("开始导入文章, 文件名: {}, 大小: {} bytes, 用户: {}", originalFilename, fileSize, user.getId());

        // 3. 使用 Tika 解析文件内容
        String content;
        try (InputStream inputStream = file.getInputStream()) {
            Tika tika = new Tika();
            content = tika.parseToString(inputStream);
        } catch (IOException e) {
            log.error("读取上传文件失败: {}", originalFilename, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "读取文件失败: " + e.getMessage());
        } catch (TikaException e) {
            log.error("Tika解析文件内容失败: {}", originalFilename, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "不支持的文件格式或文件内容解析失败: " + e.getMessage());
        }

        // 4. 提取标题（去除扩展名，取文件名本身）
        String title = extractTitle(originalFilename);

        // 5. 构建草稿
        ApArticleDraft draft = new ApArticleDraft();
        draft.setTitle(title);
        draft.setContent(content);
        draft.setSummary(content.length() > 200 ? content.substring(0, 200) : content);
        draft.setAuthorId(user.getId().longValue());
        draft.setLayout((short) 1); // 默认无图文章
        draft.setStatus((byte) 0);  // 草稿状态
        draft.setCreatedTime(new Date());
        draft.setUpdatedTime(new Date());
        draft.setIsDeleted(false);

        // 6. 保存草稿
        ResponseResult result = apArticleDraftService.createDraft(draft);
        if (result.getCode() == 0 || result.getCode() == 200) {
            log.info("文章导入成功, 文件名: {}, 草稿ID: {}", originalFilename, draft.getId());
        }

        return result;
    }

    /**
     * 从文件名中提取文章标题（去除扩展名）
     */
    private String extractTitle(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0) {
            return filename.substring(0, dotIndex);
        }
        return filename;
    }
}