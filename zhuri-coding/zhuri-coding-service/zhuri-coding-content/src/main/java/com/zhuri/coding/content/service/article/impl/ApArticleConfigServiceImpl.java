package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.content.mapper.article.ApArticleConfigMapper;
import com.heima.content.service.article.ApArticleConfigService;
import com.heima.model.article.pojos.ApArticleConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class ApArticleConfigServiceImpl extends ServiceImpl<ApArticleConfigMapper, ApArticleConfig> implements ApArticleConfigService {
    /**
     * 修改文章
     * @param map
     */
    @Override
    public void updateByMap(Map map) {
        //0 下架  1 上架
        Object enable = map.get("enable");
        boolean isDown = true;
        if(enable.equals(1)){
           isDown = false;
        }
        //修改文章
        update(Wrappers.<ApArticleConfig>lambdaUpdate().eq(ApArticleConfig::getArticleId,map.get("articleId"))
                .set(ApArticleConfig::getIsDown,isDown));

    }
}