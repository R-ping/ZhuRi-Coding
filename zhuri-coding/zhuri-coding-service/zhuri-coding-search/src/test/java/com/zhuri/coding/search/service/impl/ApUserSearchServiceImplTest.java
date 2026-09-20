package com.zhuri.coding.search.service.impl;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.search.dtos.HistorySearchDto;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.search.pojos.ApUserSearch;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApUserSearchService 用户搜索历史")
class ApUserSearchServiceImplTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private ApUserSearchServiceImpl apUserSearchService;

    @BeforeEach
    void setUp() {
        ApUser user = new ApUser();
        user.setId(1001);
        user.setNickname("测试用户");
        AppThreadLocalUtil.setUser(user);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    @Nested
    @DisplayName("保存搜索历史")
    class Insert {

        @Test
        @DisplayName("保存成功：关键词已存在，更新时间")
        void testInsertExistingKeyword() {
            // Arrange
            ApUserSearch existing = new ApUserSearch();
            existing.setId("existing-id");
            existing.setUserId(1001);
            existing.setKeyword("Java");
            when(mongoTemplate.findOne(any(Query.class), eq(ApUserSearch.class)))
                    .thenReturn(existing);

            // Act
            apUserSearchService.insert("Java", 1001);

            // Assert
            verify(mongoTemplate).save(existing);
            assertNotNull(existing.getCreatedTime());
        }

        @Test
        @DisplayName("保存成功：新关键词，历史记录未满10条")
        void testInsertNewKeywordUnderLimit() {
            // Arrange
            when(mongoTemplate.findOne(any(Query.class), eq(ApUserSearch.class)))
                    .thenReturn(null);

            List<ApUserSearch> existingList = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ApUserSearch s = new ApUserSearch();
                s.setId("id-" + i);
                existingList.add(s);
            }
            when(mongoTemplate.find(any(Query.class), eq(ApUserSearch.class)))
                    .thenReturn(existingList);

            // Act
            apUserSearchService.insert("Spring", 1001);

            // Assert
            verify(mongoTemplate, times(1)).save(any(ApUserSearch.class));
        }

        @Test
        @DisplayName("保存成功：新关键词，历史记录已满10条，替换最旧的")
        void testInsertNewKeywordAtLimit() {
            // Arrange
            when(mongoTemplate.findOne(any(Query.class), eq(ApUserSearch.class)))
                    .thenReturn(null);

            List<ApUserSearch> existingList = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                ApUserSearch s = new ApUserSearch();
                s.setId("id-" + i);
                existingList.add(s);
            }
            ApUserSearch last = new ApUserSearch();
            last.setId("oldest-id");
            existingList.add(last);

            when(mongoTemplate.find(any(Query.class), eq(ApUserSearch.class)))
                    .thenReturn(existingList);

            // Act
            apUserSearchService.insert("Redis", 1001);

            // Assert
            verify(mongoTemplate).findAndReplace(
                    argThat(q -> q.getQueryObject().get("id") != null),
                    any(ApUserSearch.class));
        }
    }

    @Nested
    @DisplayName("查询搜索历史")
    class FindUserSearch {

        @Test
        @DisplayName("查询成功：返回搜索历史列表（按时间倒序）")
        void testFindUserSearchSuccess() {
            // Arrange
            List<ApUserSearch> searchList = new ArrayList<>();
            ApUserSearch s1 = new ApUserSearch();
            s1.setId("1");
            s1.setKeyword("Java");
            s1.setCreatedTime(new Date());
            searchList.add(s1);

            ApUserSearch s2 = new ApUserSearch();
            s2.setId("2");
            s2.setKeyword("Spring");
            s2.setCreatedTime(new Date());
            searchList.add(s2);

            when(mongoTemplate.find(any(Query.class), eq(ApUserSearch.class)))
                    .thenReturn(searchList);

            // Act
            ResponseResult result = apUserSearchService.findUserSearch();

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            assertTrue(result.getData() instanceof List);
            List<ApUserSearch> data = (List<ApUserSearch>) result.getData();
            assertEquals(2, data.size());
        }

        @Test
        @DisplayName("查询失败：未登录")
        void testFindUserSearchNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();

            // Act
            ResponseResult result = apUserSearchService.findUserSearch();

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("删除搜索历史")
    class DelUserSearch {

        @Test
        @DisplayName("删除成功")
        void testDelUserSearchSuccess() {
            // Arrange
            HistorySearchDto dto = new HistorySearchDto();
            dto.setId("history-id-123");

            // Act
            ResponseResult result = apUserSearchService.delUserSearch(dto);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            verify(mongoTemplate).remove(any(Query.class), eq(ApUserSearch.class));
        }

        @Test
        @DisplayName("删除失败：参数为空")
        void testDelUserSearchNullId() {
            // Arrange
            HistorySearchDto dto = new HistorySearchDto();
            dto.setId(null);

            // Act
            ResponseResult result = apUserSearchService.delUserSearch(dto);

            // Assert
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
        }

        @Test
        @DisplayName("删除失败：未登录")
        void testDelUserSearchNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();
            HistorySearchDto dto = new HistorySearchDto();
            dto.setId("history-id");

            // Act
            ResponseResult result = apUserSearchService.delUserSearch(dto);

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }
}