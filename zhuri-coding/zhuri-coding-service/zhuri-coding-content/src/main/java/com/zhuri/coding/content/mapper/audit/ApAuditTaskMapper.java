package com.zhuri.coding.content.mapper.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 统一异步审核任务 Mapper。
 *
 * <p>查询与状态流转的条件统一在服务层用 Wrapper 组装（与原三张表实现风格一致，便于对照迁移），
 * 本接口只提供 BaseMapper 的通用能力，不额外定义 SQL —— 避免列名以裸字符串散落在注解里
 * （项目里曾出现「方法无人调用导致列名写错静默潜伏」的情况）。
 */
@Mapper
public interface ApAuditTaskMapper extends BaseMapper<ApAuditTask> {
}
