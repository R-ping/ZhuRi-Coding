package com.zhuri.coding.content.mapper.coding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户编码统计 Mapper。
 *
 * <p>更新走"读-改-写"（selectOne + updateById/insert）：tag_stats 需要在既有 JSON 上合并，
 * 无法用单条原子 SQL 表达；单用户并发提交概率低，且统计为可容忍最终一致的聚合值。
 * 唯一键 uk_user 兜底并发首次插入。</p>
 */
@Mapper
public interface ApCodingUserStatMapper extends BaseMapper<ApCodingUserStat> {
}