package com.heima.user.service.impl;

import com.heima.apis.article.IArticleClient;
import com.heima.apis.article.ILevelClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.pojos.ApUser;
import com.heima.user.service.UserStatisticsService;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class UserStatisticsServiceImpl implements UserStatisticsService {

    @Autowired
    private IArticleClient articleClient;

    @Autowired
    private ILevelClient levelClient;

    @Override
    public ResponseResult getUserStatistics() {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser == null) {
            return ResponseResult.okResult(new HashMap<>());
        }

        Map<String, Object> data = new HashMap<>();

        ResponseResult feignResult = articleClient.getStatisticsFeign(currentUser.getId().longValue());
        if (feignResult != null && feignResult.getData() instanceof Map) {
            data = (Map<String, Object>) feignResult.getData();
        }

        if (currentUser.getCreatedTime() != null) {
            long diff = System.currentTimeMillis() - currentUser.getCreatedTime().getTime();
            long createDays = TimeUnit.MILLISECONDS.toDays(diff);
            data.put("createDays", Math.max(createDays, 1));
        } else {
            data.put("createDays", 1);
        }

        try {
            Map<String, Object> levelData = levelClient.getUserLevelData(currentUser.getId().longValue());
            if (levelData != null) {
                data.put("levelBadge", levelData.getOrDefault("levelBadge", "ZR.1"));
                data.put("levelScore", levelData.getOrDefault("levelScore", 0));
                data.put("levelMax", levelData.getOrDefault("levelMax", 15));
                data.put("levelPercent", levelData.getOrDefault("levelPercent", 0));
                data.put("diamondCount", levelData.getOrDefault("diamondCount", 0));
                data.put("dailyLevel", levelData.getOrDefault("dailyLevel", 1));
                data.put("dailyScore", levelData.getOrDefault("dailyScore", 0));
            } else {
                data.put("levelBadge", "ZR.1");
                data.put("levelScore", 0);
                data.put("levelMax", 15);
                data.put("levelPercent", 0);
                data.put("diamondCount", 0);
                data.put("dailyLevel", 1);
                data.put("dailyScore", 0);
            }
        } catch (Exception e) {
            log.error("获取用户等级数据失败，userId: {}", currentUser.getId(), e);
            data.put("levelBadge", "ZR.1");
            data.put("levelScore", 0);
            data.put("levelMax", 15);
            data.put("levelPercent", 0);
            data.put("diamondCount", 0);
            data.put("dailyLevel", 1);
            data.put("dailyScore", 0);
        }
        log.info("查询用户统计信息成功，userId: {}", currentUser.getId());
        return ResponseResult.okResult(data);
    }
}
