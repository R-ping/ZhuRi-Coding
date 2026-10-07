package com.zhuri.coding.content.service.coding.impl;

import com.zhuri.coding.model.coding.vos.CodingResumeParseVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简历解析单元测试（Coding 延展第三层 · Stage A 补充）
 *
 * 覆盖：登录/空文件/格式白名单/大小上限、Tika 提取、文本清洗、无有效文字（扫描件）、超长截断。
 * 走真实 Tika（txt 是可解析的最小样本），只有 ThreadLocal 用户需要手工注入。
 */
@DisplayName("简历解析单元测试")
class CodingResumeParseServiceImplTest {

    private CodingResumeParseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CodingResumeParseServiceImpl();
        // @Value 在无容器单测里不注入，字段保留 Java 初始值 8000（与 yml 默认一致）；
        // 需要小上限的用例自行用 ReflectionTestUtils 覆盖
        ApUser user = new ApUser();
        user.setId(1001);
        AppThreadLocalUtil.setUser(user);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain",
            content.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 入参校验 ====================

    @Test
    @DisplayName("解析 - 未登录拒绝")
    void testNotLoggedIn() {
        AppThreadLocalUtil.clear();

        ResponseResult result = service.parse(file("resume.txt", "一份足够长的简历内容用于通过长度校验"));

        assertEquals(1, result.getCode().intValue());
    }

    @Test
    @DisplayName("解析 - 空文件拒绝")
    void testEmptyFile() {
        assertEquals(501, service.parse(file("resume.txt", "")).getCode().intValue());
        assertEquals(501, service.parse(null).getCode().intValue());
    }

    @Test
    @DisplayName("解析 - 格式白名单：仅 pdf/doc/docx/txt/md，其余拒绝")
    void testExtensionWhitelist() {
        ResponseResult rejected = service.parse(file("resume.exe", "这是一份长度足够通过校验的简历内容"));
        assertEquals(501, rejected.getCode().intValue());
        assertTrue(String.valueOf(rejected.getMessage()).contains("暂不支持该格式"));

        // 大小写不敏感
        ResponseResult accepted = service.parse(file("Resume.TXT",
            "这是一份长度足够通过校验的简历内容，需要超过三十个字符才行，再补几个字凑够。"));
        assertEquals(200, accepted.getCode().intValue());
    }

    @Test
    @DisplayName("解析 - 无扩展名拒绝")
    void testNoExtension() {
        assertEquals(501, service.parse(file("resume", "这是一份长度足够通过校验的简历内容")).getCode().intValue());
    }

    // ==================== 提取与清洗 ====================

    @Test
    @DisplayName("解析 - txt 正常提取并清洗（去 BOM、压缩空格、折叠空行）")
    void testParseAndClean() {
        String raw = "\uFEFF张三    求职意向：Java 后端\r\n\r\n\r\n\r\n掌握   Redis 与  RocketMQ\r\n"
            + "主导订单中台重构，负责分库分表方案落地\r\n";

        ResponseResult result = service.parse(file("resume.txt", raw));

        assertEquals(200, result.getCode().intValue());
        CodingResumeParseVO vo = (CodingResumeParseVO) result.getData();
        assertNotNull(vo.getText());
        assertFalse(vo.getTruncated());
        // 行内连续空格压成一个
        assertTrue(vo.getText().contains("掌握 Redis 与 RocketMQ"), vo.getText());
        // 原文 4 个连续空行应被折叠：保留的最多 2 个空行 → 最多 3 个连续 \n
        assertFalse(vo.getText().contains("\n\n\n\n"), "连续空行未折叠: " + vo.getText());
        assertTrue(vo.getText().contains("张三 求职意向：Java 后端\n\n\n掌握"), vo.getText());
        // BOM 已去除
        assertFalse(vo.getText().startsWith("\uFEFF"));
        // 首尾空白已 trim
        assertEquals(vo.getText(), vo.getText().trim());
    }

    @Test
    @DisplayName("解析 - 无有效文字（扫描件/图片版）给出可操作提示，而不是返回空文本")
    void testTooShortText() {
        ResponseResult result = service.parse(file("scan.pdf", "   \n  \n "));

        assertEquals(501, result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("粘贴文本"),
            "应引导用户改为粘贴文本：" + result.getMessage());
    }

    @Test
    @DisplayName("解析 - 超长文本截断：chars 记截断前长度、truncated=true、带显式标记")
    void testTruncated() {
        // 该用例只看截断逻辑，用小上限避免构造超长样本
        ReflectionTestUtils.setField(service, "resumeMaxChars", 50);
        String raw = "这是一份很长的简历内容，用来验证截断逻辑是否按上限生效并记录原始长度。"
            + "Java 后端三年经验，熟悉 Spring Boot、MySQL、Redis、RocketMQ、分库分表。";

        ResponseResult result = service.parse(file("resume.txt", raw));

        assertEquals(200, result.getCode().intValue());
        CodingResumeParseVO vo = (CodingResumeParseVO) result.getData();
        assertTrue(vo.getTruncated());
        // 标记前带一个换行，故 "\n...(…) 的起始下标即正文保留长度
        assertEquals(50, vo.getText().indexOf("\n...(简历内容过长，已截断)"),
            "正文应恰好保留上限 50 字");
        // chars 是截断前的长度（清洗后），必然大于上限 50
        assertTrue(vo.getChars() > 50, "chars 应记录截断前长度，实际=" + vo.getChars());
    }

    @Test
    @DisplayName("清洗 - 超长单行按上限截断并加省略号（表格排版被压平的场景）")
    void testLongLineTruncated() {
        String longLine = "技能清单 " + "Java ".repeat(200);

        String cleaned = service.clean(longLine);

        assertTrue(cleaned.endsWith("…"), "超长行应以省略号结尾");
        assertTrue(cleaned.length() < longLine.length());
    }
}
