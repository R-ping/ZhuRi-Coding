package com.zhuri.coding.search.service.impl;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.search.dtos.UserSearchDto;
import com.zhuri.coding.search.pojos.ApAssociateWords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.FindAndModifyOptions;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApAssociateWordsService 联想词管理")
class ApAssociateWordsServiceImplTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private ApAssociateWordsServiceImpl apAssociateWordsService;

    @Nested
    @DisplayName("搜索联想词")
    class Search {

        @Test
        @DisplayName("搜索成功：返回匹配的联想词列表")
        void testSearchSuccess() {
            // Arrange
            UserSearchDto dto = new UserSearchDto();
            dto.setSearchWords("Java");

            List<ApAssociateWords> wordsList = new ArrayList<>();
            ApAssociateWords word1 = new ApAssociateWords();
            word1.setId("1");
            word1.setAssociateWords("Java入门教程");
            word1.setSearchCount(100);
            wordsList.add(word1);

            ApAssociateWords word2 = new ApAssociateWords();
            word2.setId("2");
            word2.setAssociateWords("Java高级编程");
            word2.setSearchCount(50);
            wordsList.add(word2);

            when(mongoTemplate.find(any(Query.class), eq(ApAssociateWords.class)))
                    .thenReturn(wordsList);

            // Act
            ResponseResult result = apAssociateWordsService.search(dto);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            assertTrue(result.getData() instanceof List);
            List<ApAssociateWords> data = (List<ApAssociateWords>) result.getData();
            assertEquals(2, data.size());
        }

        @Test
        @DisplayName("搜索失败：搜索词为空")
        void testSearchEmptyKeyword() {
            // Arrange
            UserSearchDto dto = new UserSearchDto();
            dto.setSearchWords("");

            // Act
            ResponseResult result = apAssociateWordsService.search(dto);

            // Assert
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
            verify(mongoTemplate, never()).find(any(Query.class), any(Class.class));
        }

        @Test
        @DisplayName("搜索失败：搜索词为null")
        void testSearchNullKeyword() {
            // Arrange
            UserSearchDto dto = new UserSearchDto();
            dto.setSearchWords(null);

            // Act
            ResponseResult result = apAssociateWordsService.search(dto);

            // Assert
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
        }

        @Test
        @DisplayName("搜索成功：搜索词为空白字符串，视为无效")
        void testSearchBlankKeyword() {
            // Arrange
            UserSearchDto dto = new UserSearchDto();
            dto.setSearchWords("   ");

            // Act
            ResponseResult result = apAssociateWordsService.search(dto);

            // Assert
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("增加搜索次数")
    class IncrementSearchCount {

        @Test
        @DisplayName("增加成功：关键词存在，searchCount+1")
        void testIncrementSearchCountSuccess() {
            // Arrange
            when(mongoTemplate.findAndModify(
                    any(Query.class),
                    any(Update.class),
                    any(FindAndModifyOptions.class),
                    eq(ApAssociateWords.class)))
                    .thenReturn(new ApAssociateWords());

            // Act
            apAssociateWordsService.incrementSearchCount("Java");

            // Assert
            verify(mongoTemplate).findAndModify(
                    any(Query.class),
                    any(Update.class),
                    any(FindAndModifyOptions.class),
                    eq(ApAssociateWords.class));
        }

        @Test
        @DisplayName("增加成功：关键词为空，不执行任何操作")
        void testIncrementSearchCountEmptyKeyword() {
            // Act
            apAssociateWordsService.incrementSearchCount("");

            // Assert
            verify(mongoTemplate, never()).findAndModify(
                    any(Query.class), any(Update.class), any(FindAndModifyOptions.class), any(Class.class));
        }

        @Test
        @DisplayName("增加成功：关键词为null，不执行任何操作")
        void testIncrementSearchCountNullKeyword() {
            // Act
            apAssociateWordsService.incrementSearchCount(null);

            // Assert
            verify(mongoTemplate, never()).findAndModify(
                    any(Query.class), any(Update.class), any(FindAndModifyOptions.class), any(Class.class));
        }

        @Test
        @DisplayName("增加成功：关键词带前后空格，自动trim")
        void testIncrementSearchCountTrimKeyword() {
            // Arrange
            when(mongoTemplate.findAndModify(
                    any(Query.class),
                    any(Update.class),
                    any(FindAndModifyOptions.class),
                    eq(ApAssociateWords.class)))
                    .thenReturn(new ApAssociateWords());

            // Act
            apAssociateWordsService.incrementSearchCount("  Java  ");

            // Assert
            verify(mongoTemplate).findAndModify(
                    argThat(q -> q.getQueryObject().get("associateWords").toString().contains("Java")),
                    any(Update.class),
                    any(FindAndModifyOptions.class),
                    eq(ApAssociateWords.class));
        }
    }
}