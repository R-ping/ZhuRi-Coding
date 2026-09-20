package com.heima.content.behavior.service.impl;

import com.heima.model.behavior.BehaviorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 浏览课程行为处理器
 * <p>
 * 浏览课程与浏览文章共用同一套记录逻辑（插入行为记录、计算逐日等级分）。
 * 逐日等级任务上"浏览课程"并入"浏览1篇文章/课程"（browse_article）统计。
 * </p>
 */
@Slf4j
@Component
public class CourseBrowseBehaviorHandler extends BrowseBehaviorHandler {

    @Override
    public BehaviorType getType() {
        return BehaviorType.BROWSE_COURSE;
    }
}
