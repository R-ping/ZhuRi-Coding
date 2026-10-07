package com.zhuri.coding.content.service.coding.impl;

import com.zhuri.coding.content.service.coding.CodingResumeService;
import com.zhuri.coding.model.coding.vos.CodingResumeParseVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历解析实现（Coding 延展第三层 · Stage A 补充）
 *
 * <p>解析链路：登录校验 → 格式/大小白名单 → Tika 提取 → 文本清洗 → 长度校验与截断。
 * 复用 {@code ContentImportServiceImpl} 既有的 Tika 用法（Tika 3.3.1 已在 content 模块依赖中）。</p>
 *
 * <p>刻意不做的事：<b>不落库、不上传对象存储、不落磁盘</b>。简历是敏感个人信息，
 * 只在内存里走一趟，前端拿到文本后自己决定是否保存。</p>
 */
@Slf4j
@Service
public class CodingResumeParseServiceImpl implements CodingResumeService {

    /** 允许的简历文件扩展名（小写） */
    private static final List<String> ALLOWED_EXTENSIONS =
        Arrays.asList("pdf", "doc", "docx", "txt", "md");

    /** 文件大小上限：简历一般远小于此，留足扫描件余量 */
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024L;

    /** 解析出的有效文本下限：低于此值基本不是一份简历（如扫描件提取为空） */
    private static final int MIN_TEXT_CHARS = 30;

    /** 连续空行折叠阈值 */
    private static final int MAX_CONSECUTIVE_BLANK_LINES = 2;

    /** 单行最大长度（超长行多为表格排版被压平，换取 prompt 可读性） */
    private static final int MAX_LINE_CHARS = 500;

    private static final Tika TIKA = new Tika();

    /** 简历文本上限（字符），超出截断并显式标记，与开面入参上限保持一致 */
    @Value("${app.coding.interview.resume-max-chars:8000}")
    private int resumeMaxChars = 8000;

    @Override
    public ResponseResult parse(MultipartFile file) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        if (file == null || file.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请选择要上传的简历文件");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文件名不能为空");
        }

        String extension = extractExtension(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "暂不支持该格式，请上传 PDF / DOC / DOCX / TXT / MD");
        }

        long fileSize = file.getSize();
        if (fileSize > MAX_FILE_BYTES) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "文件大小不能超过 " + (MAX_FILE_BYTES / 1024 / 1024) + "MB");
        }

        log.info("解析简历: userId={}, 文件名={}, 大小={} bytes", user.getId(), originalFilename, fileSize);

        String raw;
        try (InputStream inputStream = file.getInputStream()) {
            raw = TIKA.parseToString(inputStream);
        } catch (IOException e) {
            log.error("读取简历文件失败: {}", originalFilename, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "读取文件失败，请重试");
        } catch (TikaException e) {
            log.error("Tika 解析简历失败: {}", originalFilename, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR,
                "文件内容解析失败，请确认文件未损坏或改为粘贴文本");
        }

        String cleaned = clean(raw);
        if (cleaned.length() < MIN_TEXT_CHARS) {
            // 典型场景：扫描版 PDF / 图片型简历，Tika 提不出文字层
            log.warn("简历未提取到有效文字: userId={}, 文件名={}, 提取长度={}",
                user.getId(), originalFilename, cleaned.length());
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "没有提取到文字，可能是扫描件或图片版简历，请改为直接粘贴文本");
        }

        boolean truncated = cleaned.length() > resumeMaxChars;
        String text = truncated ? cleaned.substring(0, resumeMaxChars) + "\n...(简历内容过长，已截断)" : cleaned;

        CodingResumeParseVO vo = new CodingResumeParseVO();
        vo.setText(text);
        vo.setChars(cleaned.length());
        vo.setTruncated(truncated);

        log.info("简历解析完成: userId={}, 提取={} 字, 截断={}", user.getId(), cleaned.length(), truncated);
        return ResponseResult.okResult(vo);
    }

    /**
     * 文本清洗：统一换行、压缩空白、折叠空行、限制单行长度。
     *
     * <p>PDF 提取结果普遍带 BOM、\r\n、逐字空格与大量空行；不清洗会白白吃掉 prompt 预算
     * （简历是按字符数截断的，脏字符挤占的是配额本身）。</p>
     */
    String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');

        StringBuilder sb = new StringBuilder(normalized.length());
        int blankRun = 0;
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.replaceAll("[ \\t\\x0B\\f]+", " ").trim();
            if (trimmed.isEmpty()) {
                blankRun++;
                // 折叠连续空行，但保留段落间隔
                if (blankRun <= MAX_CONSECUTIVE_BLANK_LINES) {
                    sb.append('\n');
                }
                continue;
            }
            blankRun = 0;
            if (trimmed.length() > MAX_LINE_CHARS) {
                trimmed = trimmed.substring(0, MAX_LINE_CHARS) + "…";
            }
            sb.append(trimmed).append('\n');
        }
        return sb.toString().trim();
    }

    private String extractExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
