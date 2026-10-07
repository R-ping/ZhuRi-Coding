package com.zhuri.coding.content.mapper.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 运营操作审计日志 Mapper（库：leadnews_article）。
 *
 * <p>只写不读——阶段一没有"查询审计日志"的运营界面，日志先保证落下来。
 * 将来做审计查询页时，{@code idx_user_time} / {@code idx_module_time} / {@code idx_target}
 * 三条索引分别对应"某人做过什么""某模块发生过什么""某对象被怎么处置过"三种查询形态。
 */
@Mapper
public interface ApAdminAuditLogMapper extends BaseMapper<ApAdminAuditLog> {
}
