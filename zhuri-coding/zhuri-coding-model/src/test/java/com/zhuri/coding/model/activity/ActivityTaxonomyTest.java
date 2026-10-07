package com.zhuri.coding.model.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 活动分类体系单测。
 *
 * <p>这里守的是"取值只写在建表注释里"这类列的老问题：没有白名单时，录入打错一个字母
 * 不会报错，只会让这条活动在按分类筛选时永远出不来 —— 运营看到的是"我刚建的活动怎么没了"。
 * 因此关键断言是两件事：<b>大小写与空格被归一</b>（否则 C 端的等值匹配会漏数据）、
 * <b>白名单与 DDL 注释一字不差</b>（少一个分类就等于把一个录入入口删掉了）。
 */
@DisplayName("活动分类体系（ActivityTaxonomy）")
class ActivityTaxonomyTest {

    @Test
    @DisplayName("归一化：大小写、前后空格都容忍，空白归一为 null")
    void normalize() {
        assertEquals("article", ActivityTaxonomy.normalize("  ARTICLE "));
        assertEquals("devtools", ActivityTaxonomy.normalize("DevTools"));
        assertNull(ActivityTaxonomy.normalize(null));
        assertNull(ActivityTaxonomy.normalize("   "));
    }

    @Test
    @DisplayName("活动类型白名单：article / pin，其它不认")
    void types() {
        assertEquals(2, ActivityTaxonomy.TYPES.size());
        assertEquals("article,pin", String.join(",", ActivityTaxonomy.TYPES));
        assertEquals("article", ActivityTaxonomy.DEFAULT_TYPE, "默认值要与 DDL 列默认一致");

        assertTrue(ActivityTaxonomy.isValidType("article"));
        assertTrue(ActivityTaxonomy.isValidType("PIN"), "大小写要容忍");
        assertTrue(ActivityTaxonomy.isValidType(" pin "), "首尾空白要容忍");
        assertFalse(ActivityTaxonomy.isValidType("pins"), "多一个字母的形态必须被拦住");
        assertFalse(ActivityTaxonomy.isValidType(null));
        assertFalse(ActivityTaxonomy.isValidType(""));
    }

    @Test
    @DisplayName("活动分类白名单：与 DDL 注释列出的 8 个分区完全一致")
    void categories() {
        assertEquals(8, ActivityTaxonomy.CATEGORIES.size());
        assertEquals("hot,backend,frontend,android,ios,ai,devtools,codelife",
            String.join(",", ActivityTaxonomy.CATEGORIES));
        assertEquals("hot", ActivityTaxonomy.DEFAULT_CATEGORY, "默认值要与 DDL 列默认一致");

        for (String category : ActivityTaxonomy.CATEGORIES) {
            assertTrue(ActivityTaxonomy.isValidCategory(category), category);
            assertTrue(ActivityTaxonomy.isValidCategory(category.toUpperCase()), category);
        }
        // 打错一个字母的形态必须被拦住 —— 这正是这个白名单存在的理由
        assertFalse(ActivityTaxonomy.isValidCategory("devtool"));
        assertFalse(ActivityTaxonomy.isValidCategory("codelift"));
        assertFalse(ActivityTaxonomy.isValidCategory("开发工具"));
        assertFalse(ActivityTaxonomy.isValidCategory(null));
    }

    @Test
    @DisplayName("错误提示文案里带全可选值（否则调用方只知道错了、不知道该改成什么）")
    void textListsAllOptions() {
        assertEquals("article/pin", ActivityTaxonomy.typesAsText());
        assertTrue(ActivityTaxonomy.categoriesAsText().startsWith("hot/backend/frontend"));
        assertEquals(List.of("article", "pin"), List.copyOf(ActivityTaxonomy.TYPES));
    }
}
