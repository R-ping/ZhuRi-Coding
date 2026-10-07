package com.zhuri.coding.content.service.ops;

import com.zhuri.coding.model.ops.vos.CurrentPopupVO;

import java.util.List;

/**
 * C 端 · 运营位读接口（Banner 轮播 + 当前弹窗）。
 *
 * <p><b>弹窗为什么需要登录</b>："关闭后本期不再弹"要落到用户身份上 ——
 * 未登录的关闭记录没有地方落（没有 userId 可记），要么每次都弹（烦），
 * 要么从弹（同样烦）。所以 current 只对登录用户生效：未登录返回空，不报
 * NEED_LOGIN —— 弹窗是首页的可选增值信息，为它打断未登录用户毫无必要。
 *
 * <p><b>关闭记录为什么在 Redis 而不是 MySQL</b>：它是纯会话性偏好 ——
 * 丢了的最坏后果是"重弹一次"（用户再点一下关闭），自己会痊愈；
 * 不值得为它建表 + 建索引 + 进备份。TTL 取弹窗结束时间，弹窗下线记录自然蒸发。
 */
public interface OpsBannerService {

    /**
     * 首页轮播：启用中且在时间窗内，按展示顺序排列。
     *
     * <p>可见性 = {@code status=1} 且（无开始时间或已开始）且（无结束时间或未结束）。
     * 结束时间"已到点"的 Banner 立即从 C 端消失，不需要等任何定时任务
     * —— 判定在读时做，这是"到点自动上下线"的实现方式。
     */
    List<com.zhuri.coding.model.ops.vos.BannerVO> listBanners();

    /**
     * 当前弹窗：生效中且用户未关闭的最新一条；没有返回 null。
     *
     * <p>多条启用中时取 id 最新的一条（运营"新弹窗盖旧弹窗"的直觉）。
     */
    CurrentPopupVO currentPopup(Long userId);

    /**
     * 用户关闭弹窗（幂等：重复关闭无害）。
     *
     * @return 弹窗不存在返回 false；其余（含 Redis 失败被吞）返回 true
     */
    boolean closePopup(Long userId, Long popupId);
}
