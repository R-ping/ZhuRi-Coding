package com.zhuri.coding.content.service.order.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.course.ApCourseDiscountMapper;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.course.dtos.CourseDiscountDto;
import com.zhuri.coding.model.course.pojos.ApCourseDiscount;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DiscountServiceImpl 单元测试（课程折扣码管理）
 *
 * @Service 依赖单 mapper，@Mock 注入。接口实现在 order 包下实现 DiscountService。
 * 覆盖：
 * - createDiscount 参数校验 / 默认 code 生成与默认值 / 显式 code；
 * - listDiscounts 列表查询；
 * - disableDiscount 折扣码不存在 / 正常下线；
 * - validateDiscount 空码、课程不匹配、过期、次数用尽、成功；
 * - getDiscountByCode；
 * - consumeDiscountCode 原子递增成功与否；
 * - validateDiscountForPreview 校验并回显折扣信息。
 */
class DiscountServiceImplTest {

    @Mock
    private ApCourseDiscountMapper discountMapper;

    @InjectMocks
    private DiscountServiceImpl discountService;

    private final Long courseId = 10L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCourseDiscount.class);
    }

    private ApCourseDiscount discount() {
        ApCourseDiscount d = new ApCourseDiscount();
        d.setId(1L);
        d.setCourseId(courseId);
        d.setCode("COURSE88");
        d.setDiscountType(ApCourseDiscount.DiscountType.FIXED.getCode());
        d.setDiscountValue(new BigDecimal("30"));
        d.setMaxUses(100);
        d.setUsedCount(0);
        d.setStatus(1);
        d.setStartTime(new Date(System.currentTimeMillis() - 10000));
        d.setEndTime(new Date(System.currentTimeMillis() + 10000));
        d.setCreatedTime(new Date());
        return d;
    }

    private CourseDiscountDto dto(String code, Integer maxUses) {
        CourseDiscountDto d = new CourseDiscountDto();
        d.setCourseId(courseId);
        d.setDiscountType(ApCourseDiscount.DiscountType.FIXED.getCode());
        d.setDiscountValue(new BigDecimal("30"));
        d.setCode(code);
        d.setMaxUses(maxUses);
        return d;
    }

    @Test
    @DisplayName("createDiscount 必填参数缺失返回参数错误")
    void createDiscountMissingParam() {
        CourseDiscountDto d = new CourseDiscountDto();
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                discountService.createDiscount(d, 100L).getCode());
        verify(discountMapper, never()).insert(any(ApCourseDiscount.class));
    }

    @Test
    @DisplayName("createDiscount 成功且缺省值自动补齐")
    void createDiscountOkDefaults() {
        CourseDiscountDto d = dto(null, null);
        ApCourseDiscount saved = new ApCourseDiscount();
        saved.setId(1L);
        when(discountMapper.insert(any(ApCourseDiscount.class))).thenReturn(1);

        ResponseResult r = discountService.createDiscount(d, 100L);
        assertEquals(200, r.getCode());
        ApCourseDiscount created = (ApCourseDiscount) r.getData();
        assertTrue(created.getCode().startsWith("COURSE")); // 自动生成
        assertEquals(100, created.getMaxUses()); // 默认上限
        assertEquals(0, created.getUsedCount());
        assertEquals(1, created.getStatus());
        assertNotNull(created.getStartTime());
        assertNotNull(created.getEndTime());
        verify(discountMapper).insert(any(ApCourseDiscount.class));
    }

    @Test
    @DisplayName("createDiscount 显式 code 与 maxUses 生效")
    void createDiscountExplicit() {
        when(discountMapper.insert(any(ApCourseDiscount.class))).thenReturn(1);
        ResponseResult r = discountService.createDiscount(dto("MYCODE", 5), 100L);
        ApCourseDiscount created = (ApCourseDiscount) r.getData();
        assertEquals("MYCODE", created.getCode());
        assertEquals(5, created.getMaxUses());
    }

    @Test
    @DisplayName("listDiscounts 返回列表")
    void listDiscounts() {
        when(discountMapper.selectList(any())).thenReturn(List.of(discount()));
        Map<?, ?> data = (Map<?, ?>) discountService.listDiscounts(courseId, 100L).getData();
        assertEquals(1, ((List<?>) data.get("list")).size());
    }

    @Test
    @DisplayName("disableDiscount 折扣码不存在")
    void disableDiscountNotExist() {
        when(discountMapper.selectById(99L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                discountService.disableDiscount(99L, 100L).getCode());
    }

    @Test
    @DisplayName("disableDiscount 正常下线")
    void disableDiscountOk() {
        when(discountMapper.selectById(1L)).thenReturn(discount());
        ResponseResult r = discountService.disableDiscount(1L, 100L);
        assertEquals(200, r.getCode());
        verify(discountMapper).updateById(any(ApCourseDiscount.class));
    }

    @Test
    @DisplayName("validateDiscount 空码返回 null")
    void validateDiscountEmptyCode() {
        assertNull(discountService.validateDiscount("", courseId));
        assertNull(discountService.validateDiscount(null, courseId));
    }

    @Test
    @DisplayName("validateDiscount 成功")
    void validateDiscountOk() {
        when(discountMapper.selectOne(any())).thenReturn(discount());
        assertNotNull(discountService.validateDiscount("COURSE88", courseId));
    }

    @Test
    @DisplayName("validateDiscount 返回空时直接判无效")
    void validateDiscountNullRow() {
        when(discountMapper.selectOne(any())).thenReturn(null);
        assertNull(discountService.validateDiscount("X", courseId));
    }

    @Test
    @DisplayName("validateDiscount 课程不匹配返回 null")
    void validateDiscountCourseMismatch() {
        ApCourseDiscount d = discount();
        d.setCourseId(999L);
        when(discountMapper.selectOne(any())).thenReturn(d);
        assertNull(discountService.validateDiscount("COURSE88", courseId));
    }

    @Test
    @DisplayName("validateDiscount 过期返回 null")
    void validateDiscountExpired() {
        ApCourseDiscount d = discount();
        d.setEndTime(new Date(System.currentTimeMillis() - 10000));
        when(discountMapper.selectOne(any())).thenReturn(d);
        assertNull(discountService.validateDiscount("COURSE88", courseId));
    }

    @Test
    @DisplayName("validateDiscount 使用次数用尽返回 null")
    void validateDiscountUsedUp() {
        ApCourseDiscount d = discount();
        d.setUsedCount(100);
        when(discountMapper.selectOne(any())).thenReturn(d);
        assertNull(discountService.validateDiscount("COURSE88", courseId));
    }

    @Test
    @DisplayName("getDiscountByCode 空码返回 null 否则查询")
    void getDiscountByCode() {
        assertNull(discountService.getDiscountByCode(""));
        when(discountMapper.selectOne(any())).thenReturn(discount());
        assertNotNull(discountService.getDiscountByCode("COURSE88"));
    }

    @Test
    @DisplayName("consumeDiscountCode 空码返回 false，成功返回 true")
    void consumeDiscountCode() {
        assertFalse(discountService.consumeDiscountCode(""));
        when(discountMapper.incrementUsedCountAtomic("COURSE88")).thenReturn(1);
        assertTrue(discountService.consumeDiscountCode("COURSE88"));
        when(discountMapper.incrementUsedCountAtomic("COURSE88")).thenReturn(0);
        assertFalse(discountService.consumeDiscountCode("COURSE88"));
    }

    @Test
    @DisplayName("validateDiscountForPreview 无效码返回错误、有效码回显信息")
    void validateDiscountForPreview() {
        when(discountMapper.selectOne(any())).thenReturn(discount());
        ResponseResult ok = discountService.validateDiscountForPreview("COURSE88", courseId);
        assertEquals(200, ok.getCode());

        when(discountMapper.selectOne(any())).thenReturn(null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                discountService.validateDiscountForPreview("X", courseId).getCode());
    }
}