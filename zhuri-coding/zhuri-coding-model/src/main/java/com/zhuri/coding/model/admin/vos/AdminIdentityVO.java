package com.zhuri.coding.model.admin.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 「我是谁、我能做什么」——运营后台前端启动时调一次，用来决定菜单显示什么。
 *
 * <p><b>为什么需要它</b>：前端不能靠"某个接口返回 403"来推断自己的权限。
 * 那样只有一个后果 —— 菜单全都渲染出来，点一个弹一个 403，用户以为系统坏了。
 * 所以必须有一个明确回答"我的权限集合"的接口。
 *
 * <p><b>{@code roleCodes} 与 {@code permissions} 都返回</b>：前者用于显示"你是运营/审核员"，
 * 后者用于逐项控制按钮。只给角色会让前端自己维护一张"角色→权限"的映射表，
 * 那张表必然与后端 {@code AdminRole} 漂移。
 *
 * <p><b>{@code permissions} 是权限并集</b>，一人多角色时已合并，前端不必自己算。
 */
@Data
public class AdminIdentityVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 运营账号ID（ap_admin_account.id） */
    private Integer accountId;

    private String username;

    private String nickName;

    /**
     * 是否有任一运营角色。
     *
     * <p>为 false 时接口仍返回 200（不是 403）——"登录成功但没开通权限"要能被前端区分出来，
     * 否则只会显示一个语焉不详的 403，运营得去猜是自己没权限还是系统故障。
     *
     * <p>类型刻意用包装类型 {@code Boolean} 而非 {@code boolean}：这样 getter 是
     * {@code getIsAdmin()}，按 JavaBeans 规则属性名就是 {@code isAdmin}，JSON 字段与前端
     * 写的 {@code isAdmin} 一致。若用原始类型 {@code boolean}，getter 会是 {@code isAdmin()}，
     * JavaBeans 规则把 {@code is} 前缀剥掉，序列化出来变成 {@code "admin"} —— 前端按
     * {@code isAdmin} 取到 undefined，而这种错只在联调时才暴露。
     */
    private Boolean isAdmin;

    /** 角色编码列表（原样返回，含认不出的编码，便于排查配错） */
    private List<String> roleCodes;

    /** 权限并集（权限点编码列表） */
    private List<String> permissions;

    /** 1 表示仍在使用初始口令，前端据此强提示改密 */
    private Integer mustChangePassword;
}
