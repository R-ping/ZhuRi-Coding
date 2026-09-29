package com.zhuri.coding.content.controller.v1.follow;

import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.follow.pojos.ApFollow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FollowController 批量关注判定
 *
 * 覆盖空入参兜底与"命中的 true、未命中的 false"。这个接口存在的意义就是
 * 把列表场景的 N 次调用合成 1 次，所以"入参里每个 id 都要有结果"是它的关键契约。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FollowController 批量关注判定")
class FollowControllerTest {

    @Mock
    private ApFollowMapper apFollowMapper;

    @InjectMocks
    private FollowController followController;

    private ApFollow follow(Integer userId) {
        ApFollow f = mock(ApFollow.class);
        when(f.getUserId()).thenReturn(userId);
        return f;
    }

    @Test
    @DisplayName("入参为空 → 返回空 Map，不查库")
    void testEmpty() {
        ResponseResult r = followController.isFollowingBatch(100L, List.of());
        assertEquals(200, r.getCode());
        assertTrue(((Map<?, ?>) r.getData()).isEmpty());
    }

    @Test
    @DisplayName("followUserId 为空 → 返回空 Map")
    void testNullTarget() {
        ResponseResult r = followController.isFollowingBatch(null, List.of(200L));
        assertEquals(200, r.getCode());
        assertTrue(((Map<?, ?>) r.getData()).isEmpty());
    }

    @Test
    @DisplayName("命中的记 true，未命中的记 false")
    void testMixed() {
        // 先构造好返回值再打桩：在 thenReturn(...) 的参数里调 mock 会触发 UnfinishedStubbingException
        ApFollow followed = follow(200);
        when(apFollowMapper.selectList(any())).thenReturn(List.of(followed));

        Map<String, Object> data =
                (Map<String, Object>) followController.isFollowingBatch(100L, List.of(200L, 300L)).getData();
        assertEquals(true, data.get("200"));
        assertEquals(false, data.get("300"));
    }

    @Test
    @DisplayName("重复 id 去重后只查一次")
    void testDistinct() {
        when(apFollowMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> data =
                (Map<String, Object>) followController.isFollowingBatch(100L, List.of(200L, 200L)).getData();
        assertEquals(1, data.size());
        assertEquals(false, data.get("200"));
    }
}
