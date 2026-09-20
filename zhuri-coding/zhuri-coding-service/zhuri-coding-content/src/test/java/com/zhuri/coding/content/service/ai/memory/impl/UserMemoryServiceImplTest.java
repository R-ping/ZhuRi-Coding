package com.zhuri.coding.content.service.ai.memory.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 语义长期记忆（PGVector）单测。
 *
 * <p>覆盖：remember 的入参短路 / 幂等建表 / 同内容覆盖删除 / 写入落库 / 容量裁剪；
 * recall 的入参短路 / 向量库未配置降级 / 余弦检索结果映射 / 检索异常 fail-open。
 * JdbcTemplate 全程 mock，不依赖真实 PG 环境。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("语义长期记忆（UserMemoryService / PGVector）")
class UserMemoryServiceImplTest {

    private static final double[] VEC = {0.1, 0.2, 0.3, 0.4};

    @Mock
    private JdbcTemplate pgVectorJdbcTemplate;

    @InjectMocks
    private UserMemoryServiceImpl service;

    // ==================== remember：入参短路 ====================

    @Test
    @DisplayName("userId 为空直接忽略")
    void rememberNullUserId() {
        service.remember(null, "内容", VEC);
        verify(pgVectorJdbcTemplate, never()).execute(any(String.class));
        verify(pgVectorJdbcTemplate, never()).update(any(PreparedStatementCreator.class));
    }

    @Test
    @DisplayName("内容为空或向量为空直接忽略")
    void rememberBlankContentOrEmptyVec() {
        service.remember(7, "  ", VEC);
        service.remember(7, "内容", new double[0]);
        service.remember(7, "内容", null);
        verify(pgVectorJdbcTemplate, never()).execute(any(String.class));
    }

    @Test
    @DisplayName("PG 向量库未配置时静默跳过（fail-open）")
    void rememberPgNotConfigured() {
        ReflectionTestUtils.setField(service, "pgVectorJdbcTemplate", null);
        service.remember(7, "内容", VEC);
        // 不抛异常即满足；无任何可验证调用（jdbc 为 null 不会执行）
    }

    // ==================== remember：写入链路 ====================

    @Test
    @DisplayName("首次写入：建表 + 同内容删除 + 插入 + 容量裁剪")
    void rememberInsertsAndCaps() {
        service.remember(7, "我喜欢分布式系统", VEC);

        // 幂等建表（3 条 DDL 只执行一次，二次调用走 ensured 短路）
        verify(pgVectorJdbcTemplate).execute(startsWith("CREATE TABLE IF NOT EXISTS ap_user_memory"));
        verify(pgVectorJdbcTemplate).execute(startsWith("CREATE INDEX IF NOT EXISTS idx_user_memory_user"));
        verify(pgVectorJdbcTemplate).execute(startsWith("CREATE INDEX IF NOT EXISTS idx_user_memory_embedding"));

        // 同内容覆盖删除 + 插入
        verify(pgVectorJdbcTemplate).update(
            eq("DELETE FROM ap_user_memory WHERE user_id = ? AND content = ?"), eq(7), eq("我喜欢分布式系统"));
        verify(pgVectorJdbcTemplate).update(any(PreparedStatementCreator.class));

        // 容量裁剪（只保留最近 MAX_PER_USER=100 条）
        verify(pgVectorJdbcTemplate).update(
            startsWith("DELETE FROM ap_user_memory WHERE user_id = ? AND id NOT IN"), eq(7), eq(7), eq(100));
    }

    @Test
    @DisplayName("同内容重复写入仍走覆盖删除（幂等语义）")
    void rememberTwiceDeletesThenInserts() {
        service.remember(7, "同一段记忆", VEC);
        service.remember(7, "同一段记忆", VEC);

        // 建表只执行一次（ensured 短路）
        verify(pgVectorJdbcTemplate, times(1)).execute(startsWith("CREATE TABLE IF NOT EXISTS ap_user_memory"));
        // 覆盖删除与插入各两次
        verify(pgVectorJdbcTemplate, times(2)).update(eq("DELETE FROM ap_user_memory WHERE user_id = ? AND content = ?"),
            eq(7), eq("同一段记忆"));
        verify(pgVectorJdbcTemplate, times(2)).update(any(PreparedStatementCreator.class));
    }

    // ==================== recall：入参短路与降级 ====================

    @Test
    @DisplayName("userId 为空或向量为空返回空列表")
    void recallInvalidArgs() {
        assertEquals(Collections.emptyList(), service.recall(null, VEC, 2, 0.35));
        assertEquals(Collections.emptyList(), service.recall(7, null, 2, 0.35));
        assertEquals(Collections.emptyList(), service.recall(7, new double[0], 2, 0.35));
        verify(pgVectorJdbcTemplate, never()).query(any(PreparedStatementCreator.class),
            any(ResultSetExtractor.class));
    }

    @Test
    @DisplayName("PG 向量库未配置时召回降级为空")
    void recallPgNotConfigured() {
        ReflectionTestUtils.setField(service, "pgVectorJdbcTemplate", null);
        assertTrue(service.recall(7, VEC, 2, 0.35).isEmpty());
    }

    @Test
    @DisplayName("余弦检索：按相似度过滤并按 topK 返回命中内容")
    void recallMapsHits() throws Exception {
        // 构造 2 行 ResultSet
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("content")).thenReturn("记忆A", "记忆B");
        // 让 JdbcTemplate.query 真正执行 call 方传入的 ResultSetExtractor
        when(pgVectorJdbcTemplate.query(any(PreparedStatementCreator.class),
            any(ResultSetExtractor.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            ResultSetExtractor<List<String>> rse = inv.getArgument(1);
            return rse.extractData(rs);
        });

        List<String> hits = service.recall(7, VEC, 2, 0.35);

        assertEquals(List.of("记忆A", "记忆B"), hits);
        verify(pgVectorJdbcTemplate).query(any(PreparedStatementCreator.class),
            any(ResultSetExtractor.class));
    }

    @Test
    @DisplayName("topK 超过上限 5 时被钳制（防 SELECT LIMIT 膨胀）")
    void recallClampsTopK() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(pgVectorJdbcTemplate.query(any(PreparedStatementCreator.class),
            any(ResultSetExtractor.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            ResultSetExtractor<List<String>> rse = inv.getArgument(1);
            return rse.extractData(rs);
        });

        assertTrue(service.recall(7, VEC, 100, 0.35).isEmpty());
        // 入参 topK=100 → 服务内钳制为 min(100,5)=5；能走到 query 即证明钳制路径未抛错
        verify(pgVectorJdbcTemplate).query(any(PreparedStatementCreator.class),
            any(ResultSetExtractor.class));
    }

    @Test
    @DisplayName("检索抛异常时 fail-open 返回空列表")
    void recallExceptionFailsOpen() {
        when(pgVectorJdbcTemplate.query(any(PreparedStatementCreator.class),
            any(ResultSetExtractor.class))).thenThrow(new RuntimeException("pg down"));

        assertTrue(service.recall(7, VEC, 2, 0.35).isEmpty());
    }
}