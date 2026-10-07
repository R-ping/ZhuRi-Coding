package com.zhuri.coding.content.mapper.coding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingInterview;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApCodingInterviewMapper extends BaseMapper<ApCodingInterview> {

    /**
     * 进行中的面试（一人同时只会有一场：开面前必查）。
     *
     * @param userId 用户ID
     * @return 进行中记录；没有则返回 null
     */
    @Select("SELECT * FROM ap_coding_interview WHERE user_id = #{userId} AND status = 1 "
        + "ORDER BY id DESC LIMIT 1")
    ApCodingInterview selectOngoing(@Param("userId") Integer userId);

    /**
     * 今日已开面试场次（进行中 + 已完成；已过期不计，避免被超时锁死）。
     *
     * @param userId   用户ID
     * @param dayStart 今日 00:00:00
     */
    @Select("SELECT COUNT(*) FROM ap_coding_interview WHERE user_id = #{userId} "
        + "AND status IN (1, 2) AND started_time >= #{dayStart}")
    long countToday(@Param("userId") Integer userId,
                    @Param("dayStart") java.util.Date dayStart);

    /**
     * 近 N 场已完成面试的提纲快照（新场次提纲去重用）。
     *
     * <p>排序用 {@code started_time} 而不是 {@code id}：现有 {@code idx_user_started(user_id, started_time)}
     * 能同时满足"该用户的过滤"与"倒序取前 N 条"，不必为这个低频查询新增索引或忍受 filesort。
     * 每用户受每日场次上限约束，命中行数本就极少。</p>
     *
     * @param userId 用户ID
     * @param limit  取最近几场
     */
    @Select("SELECT plan_snapshot FROM ap_coding_interview WHERE user_id = #{userId} AND status = 2 "
        + "ORDER BY started_time DESC LIMIT #{limit}")
    List<String> selectRecentPlanSnapshots(@Param("userId") Integer userId, @Param("limit") int limit);

    /**
     * 批量收尾超时未结束的场次（{@link com.zhuri.coding.content.schedule.CodingSessionRecoveryTask} 用）。
     *
     * <p>条件与既有 {@code expireOngoing} 单行版一致（{@code status = 1} 才改），只是把判定
     * 从"用户下次触达"搬到定时扫描，因此两条路径并发执行也不会互相覆盖。
     * {@code deadline_time IS NULL} 也算过期，与服务内 {@code current/start} 的判定口径对齐。</p>
     *
     * @param now   判定基准时间
     * @param limit 单批上限
     * @return 实际置为过期的行数
     */
    @Update("UPDATE ap_coding_interview SET status = 3 "
        + "WHERE status = 1 AND (deadline_time IS NULL OR deadline_time < #{now}) LIMIT #{limit}")
    int expireStaleOngoing(@Param("now") Date now, @Param("limit") int limit);
}