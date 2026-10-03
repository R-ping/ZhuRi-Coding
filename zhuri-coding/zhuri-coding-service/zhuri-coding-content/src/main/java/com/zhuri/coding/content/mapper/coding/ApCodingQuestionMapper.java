package com.zhuri.coding.content.mapper.coding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApCodingQuestionMapper extends BaseMapper<ApCodingQuestion> {

    /**
     * 抽一道未答过的上架题（按难度；难度传 null 表示不限难度）。
     *
     * <p>排除该用户已答过（含练习）的题目，避免"今日一题"抽到用户刚练过的题。
     * 排序用 {@code ORDER BY RAND()}：题库在启动阶段规模小（百级到千级），
     * 且每个用户一天只抽一次（命中 Redis 缓存后不再查库），全排序成本可接受；
     * 题库上量后应改为"随机偏移 + LIMIT 1"两步法。</p>
     *
     * @param userId     用户ID（排除已答）
     * @param difficulty 难度（1入门 2进阶 3挑战）；null 不限
     * @return 题目；无可用题时返回 null
     */
    @Select("SELECT q.* FROM ap_coding_question q "
        + "WHERE q.status = 1 "
        + "AND (q.difficulty = #{difficulty,jdbcType=INTEGER} OR #{difficulty,jdbcType=INTEGER} IS NULL) "
        + "AND NOT EXISTS (SELECT 1 FROM ap_coding_answer_record r "
        + "WHERE r.user_id = #{userId} AND r.question_id = q.id) "
        + "ORDER BY RAND() LIMIT 1")
    ApCodingQuestion selectRandomUnanswered(@Param("userId") Integer userId,
                                            @Param("difficulty") Integer difficulty);

    /**
     * 抽取一道上架题（不排除已答；难度传 null 表示不限难度）。
     *
     * <p>仅作抽题链路末级兜底：题库量小时用户可能把某难度未答题目答完，
     * 此时允许重复抽已答题，保证"今日一题"不断供。</p>
     */
    @Select("SELECT q.* FROM ap_coding_question q "
        + "WHERE q.status = 1 "
        + "AND (q.difficulty = #{difficulty,jdbcType=INTEGER} OR #{difficulty,jdbcType=INTEGER} IS NULL) "
        + "ORDER BY RAND() LIMIT 1")
    ApCodingQuestion selectRandomAny(@Param("difficulty") Integer difficulty);

    /**
     * 作答统计原子累加（仅在"当日一题"提交后调用）：
     * 避免读改写竞态，且不阻塞判分主流程。
     *
     * @param correct 本次是否答对（1累加答对数 0仅累加作答数）
     */
    @Update("UPDATE ap_coding_question SET answer_count = answer_count + 1, "
        + "correct_count = correct_count + #{correct} WHERE id = #{questionId}")
    int incrementAnswerStats(@Param("questionId") Long questionId, @Param("correct") int correct);
}