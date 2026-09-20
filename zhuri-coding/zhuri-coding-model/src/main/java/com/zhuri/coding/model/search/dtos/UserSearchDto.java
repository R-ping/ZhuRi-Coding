package com.zhuri.coding.model.search.dtos;

import lombok.Data;

import java.util.Date;


@Data
public class UserSearchDto {

    /**
    * 搜索关键字
    */
    String searchWords;
    /**
    * 当前页
    */
    int pageNum;
    /**
    * 分页条数
    */
    int pageSize;
    /**
    * 最小时间
    */
    Date minBehotTime;
    /**
    * 语义搜索开关（向量化增强）：true/缺省-关键词命中不足时向量召回兜底；false-纯关键词
    */
    Boolean semantic;

    public int getFromIndex(){
        if(this.pageNum<1)return 0;
        if(this.pageSize<1) this.pageSize = 10;
        return this.pageSize * (pageNum-1);
    }
}