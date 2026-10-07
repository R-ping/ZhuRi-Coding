package com.zhuri.coding.common.admin;

/**
 * 「某个账号有哪些运营角色」的取数口子。
 *
 * <p>为什么是接口而不是直接调 Feign：角色数据只存在于用户库，
 * content 服务要经 {@code IUserClient} 走一次网络，而 **user 服务自己查一次库就行**。
 * 把"角色从哪来"抽出来，判定逻辑（{@link AdminAuthInterceptor} + {@link AdminIdentity}）
 * 才能两个服务共用一份，不必为了共用而让 user 也绕一圈网络。
 *
 * <p><b>失败语义由实现方决定，但必须是 fail-closed</b>：取不到角色时要返回
 * {@link AdminIdentity#anonymous}（空权限），不要抛异常穿透到调用方 ——
 * 抛异常会让"用户服务抖了一下"变成"运营后台 500"，而空权限只是"这段时间用不了"，
 * 后者是可接受的降级，前者会让人误判成故障去改代码。
 */
public interface AdminRoleResolver {

    /**
     * 解析指定运营账号的运营身份。
     *
     * <p><b>入参是运营账号 ID（{@code ap_admin_account.id}），不是 C 端用户 ID。</b>
     * 两者是互不相交的 ID 空间，实现方必须按这个前提取数 —— 拿 C 端 ID 来查会得到空角色，
     * 看起来像"这个人没权限"，很容易被当成配置问题而不是传错了 ID。
     *
     * @param accountId 运营账号ID；为 null 时返回空身份
     * @return 永不为 null；无角色与解析失败均为空身份
     */
    AdminIdentity resolve(Integer accountId);
}
