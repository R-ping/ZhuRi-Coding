package com.zhuri.coding.content.service.circle.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.content.mapper.circle.ApCircleHotConfigMapper;
import com.zhuri.coding.content.mapper.circle.ApCircleMapper;
import com.zhuri.coding.content.mapper.circle.ApUserCircleMapper;
import com.zhuri.coding.content.mapper.circle.ClubFeaturedPinMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.service.circle.CircleService;
import com.zhuri.coding.model.circle.pojos.ApCircle;
import com.zhuri.coding.model.circle.pojos.ApCircleHotConfig;
import com.zhuri.coding.model.circle.pojos.ApUserCircle;
import com.zhuri.coding.model.circle.pojos.ClubFeaturedPin;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.circle.vos.CircleVO;
import com.zhuri.coding.utils.common.PageParamUtil;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CircleServiceImpl extends ServiceImpl<ApCircleMapper, ApCircle> implements CircleService {

    @Autowired
    private ApCircleMapper apCircleMapper;

    @Autowired
    private ApUserCircleMapper apUserCircleMapper;

    @Autowired
    private ApCircleHotConfigMapper apCircleHotConfigMapper;

    @Autowired
    private ClubFeaturedPinMapper clubFeaturedPinMapper;

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Override
    public List<CircleVO> recommend() {
        List<ApCircle> circles = apCircleMapper.selectRecommendCircles(10);
        Integer userId = getCurrentUserId();
        return circles.stream().map(c -> convertToVO(c, userId)).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> square(int page, int size) {
        // 该接口在网关公开只读白名单内（未登录可达）：page/size 必须收口，
        // 否则 size=100000 会一次拉全表，page/size 过大还会让 (page-1)*size 溢出为负导致 SQL 报错
        page = PageParamUtil.normalizePage(page);
        size = PageParamUtil.normalizeSize(size);
        int offset = PageParamUtil.offset(page, size);
        List<ApCircle> circles = apCircleMapper.selectSquareCircles(offset, size);
        long total = apCircleMapper.selectSquareCirclesCount();
        Integer userId = getCurrentUserId();
        List<CircleVO> voList = circles.stream().map(c -> convertToVO(c, userId)).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("list", voList);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    @Override
    public List<CircleVO> hot() {
        LambdaQueryWrapper<ApCircleHotConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(ApCircleHotConfig::getDisplayOrder);
        wrapper.last("LIMIT 5");
        List<ApCircleHotConfig> configs = apCircleHotConfigMapper.selectList(wrapper);
        if (configs.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> circleIds = configs.stream().map(ApCircleHotConfig::getCircleId).collect(Collectors.toList());
        List<ApCircle> circles = apCircleMapper.selectBatchIds(circleIds);
        // 保持 display_order 顺序
        Map<Long, ApCircle> circleMap = circles.stream().collect(Collectors.toMap(ApCircle::getId, c -> c));
        Integer userId = getCurrentUserId();
        List<CircleVO> voList = new ArrayList<>();
        for (ApCircleHotConfig config : configs) {
            ApCircle circle = circleMap.get(config.getCircleId());
            if (circle != null) {
                voList.add(convertToVO(circle, userId));
            }
        }
        return voList;
    }

    @Override
    public CircleVO detail(Long circleId, Integer userId) {
        ApCircle circle = apCircleMapper.selectById(circleId);
        if (circle == null) {
            return null;
        }
        return convertToVO(circle, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void join(Long circleId, Integer userId) {
        LambdaQueryWrapper<ApUserCircle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApUserCircle::getCircleId, circleId)
               .eq(ApUserCircle::getUserId, userId);
        Long count = apUserCircleMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new RuntimeException("已加入该圈子");
        }
        ApUserCircle uc = new ApUserCircle();
        uc.setCircleId(circleId);
        uc.setUserId(userId);
        uc.setCreatedTime(new Date());
        apUserCircleMapper.insert(uc);
        // 原子自增成员数：原先是“selectById → setMemberCount+1 → updateById”的读改写，
        // 并发加入时两个请求都读到旧值、各自写回 +1 → 只加了 1（丢失更新）。
        // 注意 @Transactional 只保证原子提交，不解决丢失更新，故必须下推到 SQL 原子操作。
        apCircleMapper.incrementMemberCount(circleId);
    }

    @Override
    public void leave(Long circleId, Integer userId) {
        LambdaQueryWrapper<ApUserCircle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApUserCircle::getCircleId, circleId)
               .eq(ApUserCircle::getUserId, userId);
        Long count = apUserCircleMapper.selectCount(wrapper);
        if (count == null || count == 0) {
            throw new RuntimeException("未加入该圈子");
        }
        apUserCircleMapper.delete(wrapper);
        // 同上：原子自减（下限夹到 0，与原先 Math.max(0, mc-1) 语义一致），避免并发退出丢更新
        apCircleMapper.decrementMemberCount(circleId);
    }

    @Override
    public Map<String, Object> feed(Long circleId, String tab, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        // 公开只读接口：先收口分页参数，避免 size 直传导致一次拉全表
        page = PageParamUtil.normalizePage(page);
        size = PageParamUtil.normalizeSize(size);

        if ("featured".equals(tab)) {
            LambdaQueryWrapper<ClubFeaturedPin> fpWrapper = new LambdaQueryWrapper<>();
            fpWrapper.eq(ClubFeaturedPin::getCircleId, circleId)
                     .orderByAsc(ClubFeaturedPin::getSortOrder);
            // 改用分页插件：原先用 last("LIMIT " + offset + "," + size) 做字符串拼接，
            // 破坏了 MyBatis-Plus 的参数绑定惯例（且 offset 溢出为负时会拼出非法 SQL）
            List<ClubFeaturedPin> featuredPins = clubFeaturedPinMapper
                .selectPage(new Page<>(page, size), fpWrapper).getRecords();
            if (!featuredPins.isEmpty()) {
                List<Long> pinIds = featuredPins.stream().map(ClubFeaturedPin::getPinId).collect(Collectors.toList());
                List<ApPins> pins = apPinsMapper.selectBatchIds(pinIds);
                Map<Long, ApPins> pinMap = pins.stream().collect(Collectors.toMap(ApPins::getId, p -> p));
                for (ClubFeaturedPin fp : featuredPins) {
                    ApPins pin = pinMap.get(fp.getPinId());
                    if (pin != null) {
                        list.add(pinToMap(pin));
                    }
                }
            }
        } else {
            LambdaQueryWrapper<ApPins> pinsWrapper = new LambdaQueryWrapper<>();
            pinsWrapper.eq(ApPins::getCircleId, circleId)
                       .eq(ApPins::getStatus, (byte) 9);
            if ("hot".equals(tab)) {
                pinsWrapper.orderByDesc(ApPins::getLikes);
            } else {
                pinsWrapper.orderByDesc(ApPins::getCreatedTime);
            }
            Page<ApPins> pinsPage = new Page<>(page, size);
            IPage<ApPins> pinsPageResult = apPinsMapper.selectPage(pinsPage, pinsWrapper);
            List<ApPins> pinsList = pinsPageResult.getRecords();
            for (ApPins pin : pinsList) {
                list.add(pinToMap(pin));
            }
        }

        result.put("list", list);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    @Override
    public List<CircleVO> myCircles(Integer userId) {
        LambdaQueryWrapper<ApUserCircle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApUserCircle::getUserId, userId);
        List<ApUserCircle> userCircles = apUserCircleMapper.selectList(wrapper);
        if (userCircles.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> circleIds = userCircles.stream().map(ApUserCircle::getCircleId).collect(Collectors.toList());
        List<ApCircle> circles = apCircleMapper.selectBatchIds(circleIds);
        return circles.stream().map(c -> convertToVO(c, userId)).collect(Collectors.toList());
    }

    private CircleVO convertToVO(ApCircle circle, Integer userId) {
        CircleVO vo = new CircleVO();
        vo.setId(circle.getId());
        vo.setName(circle.getName() != null ? circle.getName() : "");
        vo.setDescription(circle.getDescription() != null ? circle.getDescription() : "");
        vo.setIcon(circle.getIcon() != null ? circle.getIcon() : "");
        vo.setMemberCount(circle.getMemberCount() != null ? circle.getMemberCount() : 0);
        vo.setPinsCount(circle.getPinsCount() != null ? circle.getPinsCount() : 0);
        if (userId != null) {
            LambdaQueryWrapper<ApUserCircle> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApUserCircle::getCircleId, circle.getId())
                   .eq(ApUserCircle::getUserId, userId);
            vo.setIsJoined(apUserCircleMapper.selectCount(wrapper) > 0);
        } else {
            vo.setIsJoined(false);
        }
        return vo;
    }

    private Map<String, Object> pinToMap(ApPins pin) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", pin.getId());
        item.put("userId", pin.getUserId());
        item.put("userName", pin.getUserName() != null ? pin.getUserName() : "");
        item.put("userAvatar", pin.getUserAvatar() != null ? pin.getUserAvatar() : "");
        item.put("content", pin.getContent());
        item.put("likeCount", pin.getLikes());
        item.put("commentCount", pin.getComment());
        item.put("createdTime", pin.getCreatedTime());
        item.put("type", "pin");
        return item;
    }

    @Override
    public List<CircleVO> listByCategory(Long categoryId, int page, int size) {
        // 公开只读接口：收口分页参数（防 size 直传拉全表）
        page = PageParamUtil.normalizePage(page);
        size = PageParamUtil.normalizeSize(size);
        LambdaQueryWrapper<ApCircle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApCircle::getCategoryId, categoryId);
        wrapper.orderByAsc(ApCircle::getSortOrder);

        Page<ApCircle> circlePage = new Page<>(page, size);
        IPage<ApCircle> pageResult = apCircleMapper.selectPage(circlePage, wrapper);

        Integer userId = getCurrentUserId();
        return pageResult.getRecords().stream()
                .map(c -> convertToVO(c, userId))
                .collect(Collectors.toList());
    }

    private Integer getCurrentUserId() {
        try {
            return AppThreadLocalUtil.getUser().getId();
        } catch (Exception e) {
            return null;
        }
    }
}