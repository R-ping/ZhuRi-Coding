package com.heima.content.mapper.level;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.level.pojos.ApUserLevel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApUserLevelMapper extends BaseMapper<ApUserLevel> {

    /**
     * 按用户ID加分布式锁——悲观行锁（FOR UPDATE）。
     * <p>
     * 用于并发下串行化"每日积分/次数上限校验 + 加分落库"，防止 TOCTOU 越上限刷分与重复签到。
     * 必须在事务内调用（返回 null 表示记录不存在，调用方需保证前置创建）。
     * </p>
     * @param userId 用户ID
     * @return 该用户的等级记录；不存在返回 null
     */
    @Select("SELECT * FROM ap_user_level WHERE user_id = #{userId} FOR UPDATE")
    ApUserLevel selectByUserIdForUpdate(@Param("userId") Long userId);
}