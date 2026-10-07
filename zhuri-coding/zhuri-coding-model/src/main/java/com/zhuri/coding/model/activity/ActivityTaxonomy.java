package com.zhuri.coding.model.activity;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 活动分类体系：{@code ap_activity.activity_type} 与 {@code ap_activity.category} 的取值白名单。
 *
 * <p>这两列在建表时是自由 varchar（{@code activity_type varchar(20)}、{@code category varchar(50)}），
 * 取值只写在列注释里，没有任何约束。这类"取值只存在于注释里"的列有个固定失效方式：
 * 录入时打错一个字母（{@code devtool} / {@code DevTools} / {@code 开发工具}），
 * 数据库不报错、接口不报错，只是**这条活动在按分类筛选时永远出不来**。
 * 运营看到的是"我刚建的活动怎么没了"，而库里躺着一条看起来完全正常的记录。
 *
 * <p>所以这里把取值收成白名单，在写入前校验。三条约定：
 * <ul>
 *   <li><b>大小写不敏感</b>：{@link #normalize} 转小写后比较，写入库的也是小写 ——
 *       大小写混用只会让 C 端的 {@code WHERE category = ?} 漏掉数据；</li>
 *   <li><b>类型与分类分开维护</b>：{@code activity_type} 决定这条活动是文章征集还是沸点征集
 *       （直接影响 C 端把投稿引到哪条链路），{@code category} 只是列表上的分区，两者的变更节奏不同；</li>
 *   <li><b>白名单是代码契约而不是数据</b>：与 {@code AdminPermission} 同理 ——
 *       入库会带来"代码加了新分类、库里没加"的漂移，而漂移的结果是新分类没人能用。
 *       新增分类是一行代码 + 一次发版，而不是一次数据变更。</li>
 * </ul>
 *
 * <p><b>默认值</b>取自建表 DDL 的列默认（{@code article} / {@code hot}）。新建活动时若未指定，
 * 用这两个值补齐，保证列上永远不会落下 NULL —— 该列可空，一旦为 NULL，
 * C 端按分类筛选时它同样出不来，等于一条"存在但查不到"的活动。
 */
public final class ActivityTaxonomy {

    private ActivityTaxonomy() {
    }

    /** 类型：文章征集（投稿指向文章发布链路） */
    public static final String TYPE_ARTICLE = "article";

    /** 类型：沸点征集（投稿指向沸点发布链路） */
    public static final String TYPE_PIN = "pin";

    /** 分类：热门（默认分区） */
    public static final String CATEGORY_HOT = "hot";

    /**
     * 合法活动类型（顺序即前端下拉的展示顺序）。
     *
     * <p>与 {@link ActivityStatus} 同样用 {@link LinkedHashSet} 而非 {@code Set.of}：
     * 这个集合会直接用于生成错误提示文案，需要可复现的顺序。
     */
    public static final Set<String> TYPES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(TYPE_ARTICLE, TYPE_PIN)));

    /**
     * 合法活动分类。
     *
     * <p>取值来自建表 DDL 的列注释（{@code hot/backend/frontend/android/ios/ai/devtools/codelife}），
     * 与 C 端活动页的分区标签一一对应。少一个就等于把一个分区从录入入口里删掉了 ——
     * 所以新增分区必须同时改这里，否则运营会发现"这个分区我怎么也选不到"。
     */
    public static final Set<String> CATEGORIES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(
            CATEGORY_HOT, "backend", "frontend", "android", "ios", "ai", "devtools", "codelife")));

    /** 未指定类型时的默认值（与 DDL 列默认一致） */
    public static final String DEFAULT_TYPE = TYPE_ARTICLE;

    /** 未指定分类时的默认值（与 DDL 列默认一致） */
    public static final String DEFAULT_CATEGORY = CATEGORY_HOT;

    /**
     * 归一化：去空白 + 转小写。
     *
     * @return 归一化后的编码；入参为 null/空白时返回 {@code null}
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 是否是合法活动类型（大小写不敏感） */
    public static boolean isValidType(String raw) {
        String code = normalize(raw);
        return code != null && TYPES.contains(code);
    }

    /** 是否是合法活动分类（大小写不敏感） */
    public static boolean isValidCategory(String raw) {
        String code = normalize(raw);
        return code != null && CATEGORIES.contains(code);
    }

    /** 合法的类型清单（用于拼错误提示） */
    public static String typesAsText() {
        return String.join("/", TYPES);
    }

    /** 合法的分类清单（用于拼错误提示） */
    public static String categoriesAsText() {
        return String.join("/", CATEGORIES);
    }
}
