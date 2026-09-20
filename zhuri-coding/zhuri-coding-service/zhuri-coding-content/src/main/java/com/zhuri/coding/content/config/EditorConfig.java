package com.zhuri.coding.content.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 编辑白名单配置（前后端双常量，userId 白名单）
 * 当前编辑账号: admin (userId=4)，手机号 13511223456
 */
public class EditorConfig {

    /** 编辑账号 userId 白名单 */
    public static final Set<Integer> EDITOR_USER_IDS = new HashSet<>(Arrays.asList(4));

    /** 判断指定 userId 是否为编辑 */
    public static boolean isEditor(Integer userId) {
        return userId != null && EDITOR_USER_IDS.contains(userId);
    }
}
