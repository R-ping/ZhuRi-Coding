package com.heima.content.service.circle.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.content.mapper.circle.ApCircleHotConfigMapper;
import com.heima.content.mapper.circle.ApCircleMapper;
import com.heima.content.mapper.circle.ApUserCircleMapper;
import com.heima.content.mapper.circle.ClubFeaturedPinMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.service.circle.CircleService;
import com.heima.model.circle.pojos.ApCircle;
import com.heima.model.circle.pojos.ApCircleHotConfig;
import com.heima.model.circle.pojos.ApUserCircle;
import com.heima.model.circle.pojos.ClubFeaturedPin;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.circle.vos.CircleVO;
import com.heima.utils.thread.AppThreadLocalUtil;
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
        int offset = (page - 1) * size;
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
        ApCircle circle = apCircleMapper.selectById(circleId);
        if (circle != null) {
            circle.setMemberCount((circle.getMemberCount() != null ? circle.getMemberCount() : 0) + 1);
            apCircleMapper.updateById(circle);
        }
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
        ApCircle circle = apCircleMapper.selectById(circleId);
        if (circle != null) {
            int mc = circle.getMemberCount() != null ? circle.getMemberCount() : 0;
            circle.setMemberCount(Math.max(0, mc - 1));
            apCircleMapper.updateById(circle);
        }
    }

    @Override
    public Map<String, Object> feed(Long circleId, String tab, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();

        if ("featured".equals(tab)) {
            LambdaQueryWrapper<ClubFeaturedPin> fpWrapper = new LambdaQueryWrapper<>();
            fpWrapper.eq(ClubFeaturedPin::getCircleId, circleId)
                     .orderByAsc(ClubFeaturedPin::getSortOrder);
            int offset = (page - 1) * size;
            fpWrapper.last("LIMIT " + offset + "," + size);
            List<ClubFeaturedPin> featuredPins = clubFeaturedPinMapper.selectList(fpWrapper);
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