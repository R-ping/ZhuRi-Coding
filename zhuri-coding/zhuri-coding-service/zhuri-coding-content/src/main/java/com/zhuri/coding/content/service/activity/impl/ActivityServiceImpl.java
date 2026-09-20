package com.zhuri.coding.content.service.activity.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.mapper.activity.ApActivityMapper;
import com.zhuri.coding.content.service.activity.ActivityService;
import com.zhuri.coding.model.activity.pojos.ApActivity;
import com.zhuri.coding.model.activity.vos.ActivityVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class ActivityServiceImpl implements ActivityService {

    @Autowired
    private ApActivityMapper apActivityMapper;

    @Override
    public Map<String, Object> list(int page, int size, String type, String status, String category) {
        LambdaQueryWrapper<ApActivity> wrapper = new LambdaQueryWrapper<>();

        if (type != null && !type.trim().isEmpty()) {
            wrapper.eq(ApActivity::getActivityType, type.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(ApActivity::getStatus, status.trim());
        }
        if (category != null && !category.trim().isEmpty() && !"hot".equals(category.trim())) {
            wrapper.eq(ApActivity::getCategory, category.trim());
        }

        wrapper.orderByDesc(ApActivity::getStartDate);

        Page<ApActivity> pageParam = new Page<>(page, size);
        IPage<ApActivity> pageResult = apActivityMapper.selectPage(pageParam, wrapper);

        List<ActivityVO> voList = pageResult.getRecords().stream().map(activity -> {
            ActivityVO vo = new ActivityVO();
            BeanUtils.copyProperties(activity, vo);
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("list", voList);
        result.put("total", pageResult.getTotal());
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    @Override
    public ApActivity getById(Long id) {
        return apActivityMapper.selectById(id);
    }
}