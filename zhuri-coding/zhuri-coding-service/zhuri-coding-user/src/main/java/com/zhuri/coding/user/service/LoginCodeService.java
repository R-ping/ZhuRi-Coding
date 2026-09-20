package com.zhuri.coding.user.service;

/**
 * 手机验证码的发放、校验与消费。
 * <p>
 * 统一收敛验证码的 Redis key 规则，并保证两个安全语义：
 * <ol>
 *   <li><b>一次性</b>：校验通过即原子删除（GETDEL），同一个验证码不可能被重复使用（防重放）；</li>
 *   <li><b>错误次数受限</b>：连续错误达到 {@link #MAX_ATTEMPTS} 次即作废该验证码，阻断对短验证码的暴力枚举。</li>
 * </ol>
 */
public interface LoginCodeService {

    /** 验证码有效期（分钟），与发放时的 TTL 保持一致 */
    int CODE_TTL_MINUTES = 5;

    /** 单个验证码允许的最大错误尝试次数，超出即作废需重新获取 */
    int MAX_ATTEMPTS = 5;

    /** 场景 key 前缀，最终 key 形如 {@code socialBind:{platform}:{phone}} */
    String KEY_PREFIX = "socialBind:";

    /**
     * 写入验证码并重置错误尝试计数（重新获取即重新计数）。
     *
     * @param platform 平台标识（登录为 app，社交绑定为 github / weibo 等）
     * @param phone    手机号
     * @param code     验证码
     */
    void issueCode(String platform, String phone, String code);

    /**
     * 校验并一次性消费验证码。
     *
     * @param platform  平台标识，须与发放时一致
     * @param phone     手机号
     * @param inputCode 用户提交的验证码
     * @return 校验通过返回 true，且该验证码立即失效（不可重放）；否则 false
     */
    boolean verifyAndConsume(String platform, String phone, String inputCode);
}
