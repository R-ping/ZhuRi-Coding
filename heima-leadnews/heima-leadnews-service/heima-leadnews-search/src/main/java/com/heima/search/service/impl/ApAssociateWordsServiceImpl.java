package com.heima.search.service.impl;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.search.dtos.UserSearchDto;
import com.heima.search.pojos.ApAssociateWords;
import com.heima.search.service.ApAssociateWordsService;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ApAssociateWordsServiceImpl implements ApAssociateWordsService {

    private static final int ASSOCIATE_WORDS_LIMIT=10;
    @Autowired
    private MongoTemplate mongoTemplate;

    /**
     * 搜索联想词
     * @param dto
     * @return
     */
    @Override
    public ResponseResult search(UserSearchDto dto) {
        //1.检查参数
        if(StringUtils.isBlank(dto.getSearchWords())){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        log.info("开始搜索：{}",dto.getSearchWords());
        //2.执行查询，模糊查询
        Query query = Query.query(Criteria.where("associateWords").regex(".*?" + Pattern.quote(dto.getSearchWords()) + ".*"));
        query.with(Sort.by(Sort.Order.desc("searchCount"), Sort.Order.desc("createdTime")));
        query.limit(ASSOCIATE_WORDS_LIMIT);
        List<ApAssociateWords> apAssociateWords = mongoTemplate.find(query, ApAssociateWords.class);
        return ResponseResult.okResult(apAssociateWords);
    }

    /**
     * 增加搜索次数
     * @param keyword
     */
    @Override
    public void incrementSearchCount(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return;
        }
        String trimmedKeyword = keyword.trim();
        Query query = Query.query(Criteria.where("associateWords").is(trimmedKeyword));
        Update update = new Update().inc("searchCount", 1);
        update.setOnInsert("associateWords", trimmedKeyword);
        update.setOnInsert("createdTime", new Date());
        FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(false);
        mongoTemplate.findAndModify(query, update, options, ApAssociateWords.class);
    }

}
