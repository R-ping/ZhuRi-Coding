package com.heima.content.feign;

import com.heima.apis.article.ILevelClient;
import com.heima.content.service.level.LevelService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LevelClient implements ILevelClient {

    @Autowired
    private LevelService levelService;
    @GetMapping("/user/{userId}/info")
    public Map<String, Object> getUserLevelInfo(@PathVariable Long userId) {
        return levelService.getUserLevelInfo(userId);
    }

    @GetMapping("/user/{userId}/data")
    public Map<String, Object> getUserLevelData(@PathVariable Long userId) {
        return levelService.getUserLevelData(userId);
    }
}
