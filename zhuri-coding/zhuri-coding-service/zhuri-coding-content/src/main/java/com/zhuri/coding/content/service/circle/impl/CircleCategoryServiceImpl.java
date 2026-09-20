package com.zhuri.coding.content.service.circle.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.circle.ApCircleCategoryMapper;
import com.zhuri.coding.content.mapper.circle.ApCircleMapper;
import com.zhuri.coding.content.service.circle.CircleCategoryService;
import com.zhuri.coding.model.circle.pojos.ApCircle;
import com.zhuri.coding.model.circle.pojos.ApCircleCategory;
import com.zhuri.coding.model.circle.vos.CircleCategoryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CircleCategoryServiceImpl implements CircleCategoryService {

    @Autowired
    private ApCircleCategoryMapper apCircleCategoryMapper;

    @Autowired
    private ApCircleMapper apCircleMapper;

    @Override
    public List<CircleCategoryVO> listAll() {
        LambdaQueryWrapper<ApCircleCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(ApCircleCategory::getSortOrder);
        List<ApCircleCategory> categories = apCircleCategoryMapper.selectList(wrapper);
        if (categories.isEmpty()) {
            return new ArrayList<>();
        }
        return categories.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public CircleCategoryVO getById(Long id) {
        ApCircleCategory category = apCircleCategoryMapper.selectById(id);
        if (category == null) {
            return null;
        }
        return convertToVO(category);
    }

    private CircleCategoryVO convertToVO(ApCircleCategory category) {
        CircleCategoryVO vo = new CircleCategoryVO();
        vo.setId(category.getId());
        vo.setName(category.getName() != null ? category.getName() : "");
        vo.setSortOrder(category.getSortOrder() != null ? category.getSortOrder() : 0);

        // 统计该分类下的圈子数量
        LambdaQueryWrapper<ApCircle> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(ApCircle::getCategoryId, category.getId());
        Long count = apCircleMapper.selectCount(countWrapper);
        vo.setCircleCount(count != null ? count.intValue() : 0);

        return vo;
    }
}