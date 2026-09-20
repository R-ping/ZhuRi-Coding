package com.heima.search.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.search.dtos.HistorySearchDto;
import com.heima.model.search.dtos.UserSearchDto;
import com.heima.search.service.ApAssociateWordsService;
import com.heima.search.service.ApUserSearchService;
import com.heima.search.service.ArticleSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索模块三个 Controller 的薄层委托测试
 *
 * 直接以 @InjectMocks 调用方法，验证：
 * - ArticleSearchController：pageSize/minBehotTime 默认值补全后转发检索；
 * - ApUserSearchController / ApAssociateWordsController：原样转发历史/联想请求。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("搜索模块 Controller 委托")
class SearchControllersTest {

    @Mock
    private ArticleSearchService articleSearchService;
    @Mock
    private ApUserSearchService apUserSearchService;
    @Mock
    private ApAssociateWordsService apAssociateWordsService;

    @InjectMocks
    private ArticleSearchController articleSearchController;
    @InjectMocks
    private ApUserSearchController apUserSearchController;
    @InjectMocks
    private ApAssociateWordsController apAssociateWordsController;

    @BeforeEach
    void setUp() {
        // 无需清理：依赖均为 Mock，不涉及线程本地或真实数据
    }

    @Nested
    @DisplayName("文章检索 Controller")
    class ArticleSearch {

        @Test
        @DisplayName("pageSize=0 → 补 10；minBehotTime=null → 补当前时间，再转发")
        void testSearchDefaults() throws Exception {
            UserSearchDto dto = new UserSearchDto();
            dto.setSearchWords("Java");
            dto.setPageSize(0);
            when(articleSearchService.search(any(UserSearchDto.class)))
                    .thenReturn(ResponseResult.okResult());

            ResponseResult r = articleSearchController.search(dto);

            assertNotNull(r);
            assertEquals(10, dto.getPageSize());       // 默认条数被补全
            assertNotNull(dto.getMinBehotTime());      // 默认时间被补全
            verify(articleSearchService).search(dto);
        }

        @Test
        @DisplayName("pageSize 非 0、时间已给 → 保持原值转发")
        void testSearchKeepValues() throws Exception {
            UserSearchDto dto = new UserSearchDto();
            dto.setSearchWords("Spring");
            dto.setPageSize(5);
            when(articleSearchService.search(any(UserSearchDto.class)))
                    .thenReturn(ResponseResult.okResult());

            articleSearchController.search(dto);

            assertEquals(5, dto.getPageSize());
            verify(articleSearchService).search(dto);
        }
    }

    @Nested
    @DisplayName("搜索历史 Controller")
    class UserHistory {

        @Test
        @DisplayName("加载历史 → 转发 findUserSearch")
        void testFindUserSearch() {
            when(apUserSearchService.findUserSearch())
                    .thenReturn(ResponseResult.okResult());
            ResponseResult r = apUserSearchController.findUserSearch();
            assertNotNull(r);
            verify(apUserSearchService).findUserSearch();
        }

        @Test
        @DisplayName("删除历史 → 转发 delUserSearch")
        void testDelUserSearch() {
            HistorySearchDto dto = new HistorySearchDto();
            dto.setId("h-1");
            when(apUserSearchService.delUserSearch(dto))
                    .thenReturn(ResponseResult.okResult());
            ResponseResult r = apUserSearchController.delUserSearch(dto);
            assertNotNull(r);
            verify(apUserSearchService).delUserSearch(dto);
        }
    }

    @Nested
    @DisplayName("联想词 Controller")
    class Associate {

        @Test
        @DisplayName("联想搜索 → 转发 apAssociateWordsService.search")
        void testSearch() {
            UserSearchDto dto = new UserSearchDto();
            dto.setSearchWords("Ja");
            when(apAssociateWordsService.search(dto))
                    .thenReturn(ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID));
            ResponseResult r = apAssociateWordsController.search(dto);
            assertNotNull(r);
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
            verify(apAssociateWordsService).search(dto);
        }
    }
}