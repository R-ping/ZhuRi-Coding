package com.zhuri.coding.content.mapper.coding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 选择题题库 Mapper。
 *
 * <p><b>现在只剩能力测评（第二层）在用</b>：每日一题已换简答，读的是
 * {@code ap_coding_daily_pool}（见 {@link ApCodingDailyPoolMapper}）。
 * 原先为每日一题服务的抽题与计数方法已随选择题链路一并移除。</p>
 */
@Mapper
public interface ApCodingQuestionMapper extends BaseMapper<ApCodingQuestion> {

    /**
     * 批量随机抽题（能力测评组卷用）。
     *
     * <p>difficulty 传 null 表示不限难度；tags 非空时限定命中任一标签（弱项领域优先组卷）。
     * 调用方需多取候选并在内存中过滤同卷重复（ORDER BY RAND() 已随机化，重复率与已选数相关）。
     * 题库规模当前为百级到千级，全排序成本可接受；上量后应换"随机偏移/策略池"实现。</p>
     *
     * @param difficulty 难度（1入门 2进阶 3挑战）；null 不限
     * @param tags       标签命中列表（任一命中即可）；null/空 不限标签
     * @param limit      返回条数
     */
    @Select("<script>"
        + "SELECT * FROM ap_coding_question WHERE status = 1 "
        + "<if test='difficulty != null'>AND difficulty = #{difficulty} </if>"
        + "<if test='tags != null and tags.size() > 0'>AND ("
        + "<foreach collection='tags' item='tag' separator=' OR '>tags LIKE CONCAT('%', #{tag}, '%')</foreach>"
        + ") </if>"
        + "ORDER BY RAND() LIMIT #{limit}"
        + "</script>")
    List<ApCodingQuestion> selectRandomBatch(@Param("difficulty") Integer difficulty,
                                             @Param("tags") List<String> tags,
                                             @Param("limit") int limit);
}
