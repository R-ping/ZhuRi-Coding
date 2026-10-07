package com.zhuri.coding.content.service.activity.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.mapper.activity.ApActivityMapper;
import com.zhuri.coding.content.service.activity.ActivityService;
import com.zhuri.coding.model.activity.ActivityStatus;
import com.zhuri.coding.model.activity.pojos.ApActivity;
import com.zhuri.coding.model.activity.vos.ActivityVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 活动读路径（C 端）。
 *
 * <p><b>可见性是这个类的第一职责，不是可选项</b>：运营后台能写 {@code draft}（草稿）与
 * {@code offline}（已下线）之后，这两类活动就真的躺在同一张表里了。读路径一旦不过滤，
 * 后果是"运营存了个草稿，全站用户立刻看到了半成品"——而这种事故没有任何报错，
 * 只有等人发现。所以下面两个方法都<b>无条件</b>收口到
 * {@link ActivityStatus#isVisibleToClient}：不依赖调用方传对参数。
 *
 * <p><b>请求了不可见的状态时返回空列表，而不是忽略这个条件</b>：忽略等于把结果集放大 ——
 * 前端某个 tab 传了 {@code status=draft}（笔误、或旧版本前端），它会收到全部已发布活动，
 * 而不是"没有数据"。放大的结果集不会被任何人注意到，缩小到空至少是对得上账的。
 */
@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class ActivityServiceImpl implements ActivityService {

    @Autowired
    private ApActivityMapper apActivityMapper;

    @Override
    public Map<String, Object> list(int page, int size, String type, String status, String category) {
        String requested = ActivityStatus.normalize(status);
        // 传了状态、但它不是 C 端可见的状态（草稿/已下线，或认不出的编码）→ 空列表。
        // 注意 status 为空串/null 时不会走到这里：那是"不按状态筛"，语义完全不同。
        if (status != null && !status.isBlank() && !ActivityStatus.isVisibleToClient(requested)) {
            log.debug("[Activity] C 端请求了不可见的活动状态, status={}，返回空列表", status);
            return emptyPage(page, size);
        }

        LambdaQueryWrapper<ApActivity> wrapper = new LambdaQueryWrapper<>();

        if (type != null && !type.trim().isEmpty()) {
            wrapper.eq(ApActivity::getActivityType, type.trim());
        }
        if (requested != null) {
            wrapper.eq(ApActivity::getStatus, requested);
        } else {
            // 不筛状态时，仍然只返回已发布的三种。用 IN 而不是"排除 draft/offline"：
            // 库里将来若多出一个谁都没预料到的状态取值，IN 把它挡在外面（安全的一侧），
            // NOT IN 会把它放进 C 端。
            wrapper.in(ApActivity::getStatus, ActivityStatus.publishedStatuses());
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

    /**
     * 按 ID 查活动（仅返回对外可见的）。
     *
     * <p>草稿与已下线的活动在这里"等同于不存在"：返回 {@code null} 而不是抛异常或返回一个
     * 打了标记的对象 —— C 端拿到"这条不存在"就够了，它不需要、也不应该知道运营后台里
     * 有没有这么一条记录（那属于后台内部状态）。
     */
    @Override
    public ApActivity getById(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        ApActivity activity = apActivityMapper.selectById(id);
        if (activity == null || !ActivityStatus.isVisibleToClient(activity.getStatus())) {
            return null;
        }
        return activity;
    }

    /** 空结果页，字段与正常返回一致，前端不必为一个"没有数据"的分支另写一套取值 */
    private static Map<String, Object> emptyPage(int page, int size) {
        Map<String, Object> result = new HashMap<>();
        result.put("list", new ArrayList<>());
        result.put("total", 0L);
        result.put("page", page);
        result.put("size", size);
        return result;
    }
}
