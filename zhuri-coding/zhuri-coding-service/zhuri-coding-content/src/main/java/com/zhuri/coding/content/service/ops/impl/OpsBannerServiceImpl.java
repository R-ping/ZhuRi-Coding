package com.zhuri.coding.content.service.ops.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.ops.ApBannerMapper;
import com.zhuri.coding.content.mapper.ops.ApPopupMapper;
import com.zhuri.coding.content.service.ops.OpsBannerService;
import com.zhuri.coding.content.service.ops.PopupCloseStore;
import com.zhuri.coding.model.ops.pojos.ApBanner;
import com.zhuri.coding.model.ops.pojos.ApPopup;
import com.zhuri.coding.model.ops.vos.BannerVO;
import com.zhuri.coding.model.ops.vos.CurrentPopupVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 运营位读接口实现。
 *
 * <p><b>时间窗判定放在 SQL 条件里</b>：{@code start_time <= NOW 且 end_time > NOW}
 * （NULL 视为不限）。判定在查询时做意味着"到点自动上下线"不需要任何定时任务
 * —— 这与活动 CMS 的定时推进不同：活动要推进**状态列**（列表筛选读列值、
 * C 端与运营端共用一套筛选），而 Banner/弹窗没有状态流转，只有可见性，
 * 读时判定就是最短路径。
 *
 * <p><b>当前弹窗为什么取"最新的未关闭"而不是"全部未关闭"</b>：同一时间启用多条
 * 弹窗是运营误操作（或明确的覆盖意图），全弹出来是灾难体验；
 * 取最新一条 = 运营后启用的盖住先启用的，符合"新公告替换旧公告"的直觉。
 * 候选限 10 条：正常运营不会同时挂 10 条启用中的弹窗，超过就当异常收敛。
 */
@Slf4j
@Service
public class OpsBannerServiceImpl implements OpsBannerService {

    /** 当前弹窗候选上限：生效中的弹窗本就屈指可数，这是防运营误操作的收敛闸 */
    private static final int POPUP_CANDIDATE_LIMIT = 10;

    @Autowired
    private ApBannerMapper apBannerMapper;

    @Autowired
    private ApPopupMapper apPopupMapper;

    @Autowired
    private PopupCloseStore popupCloseStore;

    @Override
    public List<BannerVO> listBanners() {
        Date now = new Date();
        List<ApBanner> banners = apBannerMapper.selectList(new LambdaQueryWrapper<ApBanner>()
            .eq(ApBanner::getStatus, ApBanner.STATUS_ENABLED)
            .and(w -> w.isNull(ApBanner::getStartTime).or().le(ApBanner::getStartTime, now))
            .and(w -> w.isNull(ApBanner::getEndTime).or().gt(ApBanner::getEndTime, now))
            .orderByAsc(ApBanner::getSortOrder)
            .orderByDesc(ApBanner::getId));
        return banners.stream().map(b -> {
            BannerVO vo = new BannerVO();
            vo.setId(b.getId());
            vo.setImageUrl(b.getImageUrl());
            vo.setLinkUrl(b.getLinkUrl());
            return vo;
        }).toList();
    }

    @Override
    public CurrentPopupVO currentPopup(Long userId) {
        if (userId == null) {
            // 未登录：关闭记录没有身份可落，直接安静地不给（见接口注释）
            return null;
        }
        Date now = new Date();
        List<ApPopup> candidates = apPopupMapper.selectList(new LambdaQueryWrapper<ApPopup>()
            .eq(ApPopup::getStatus, ApPopup.STATUS_ENABLED)
            .le(ApPopup::getStartTime, now)
            .gt(ApPopup::getEndTime, now)
            .orderByDesc(ApPopup::getId)
            .last("LIMIT " + POPUP_CANDIDATE_LIMIT));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (ApPopup popup : candidates) {
            if (popupCloseStore.isClosed(popup.getId(), userId)) {
                continue;
            }
            CurrentPopupVO vo = new CurrentPopupVO();
            vo.setId(popup.getId());
            vo.setTitle(popup.getTitle());
            vo.setContent(popup.getContent());
            vo.setImageUrl(popup.getImageUrl());
            vo.setButtonText(popup.getButtonText());
            vo.setLinkUrl(popup.getLinkUrl());
            vo.setEndTime(popup.getEndTime());
            return vo;
        }
        return null;
    }

    @Override
    public boolean closePopup(Long userId, Long popupId) {
        if (userId == null || popupId == null || popupId <= 0) {
            return false;
        }
        ApPopup popup = apPopupMapper.selectById(popupId);
        if (popup == null) {
            // 弹窗已被删除/不存在：告诉调用方"没有这回事"，而不是当成功掩盖
            return false;
        }
        // 重复关闭无害（SET 覆盖）；到点已过的弹窗也能关（用户看到的弹窗可能在到期瞬间被点关，
        // TTL 组件有 60s 下限兜底），这个竞态不值得为它加校验
        popupCloseStore.markClosed(popupId, userId, popup.getEndTime());
        return true;
    }
}
