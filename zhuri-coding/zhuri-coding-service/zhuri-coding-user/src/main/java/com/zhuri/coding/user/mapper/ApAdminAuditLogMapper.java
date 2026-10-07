package com.zhuri.coding.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 运营操作审计日志 Mapper（库：leadnews_user）。
 *
 * <p><b>与 content 库那份是两张同名表</b>，不是一处集中存储：审计是绝对不允许丢的记录，
 * 跨服务写会引入"审计服务挂了、业务照常执行、于是没留痕"的窗口；本地表与业务同库，
 * 事务能覆盖。代价是查全量要跨库聚合，可以接受。DDL 见
 * {@code db/migrations/add_admin_audit_log.sql}。
 *
 * <p>本表在本模块**既写又读**（与 content 侧"只写不读"不同）：用户处置的"警告"没有独立业务表，
 * 审计记录本身就是警告的台账 —— 封禁名单页要显示"被警告过几次"、处置记录页要列出历史，
 * 都靠 {@code idx_target(target_type, target_id)} 与 {@code idx_module_time(module, created_time)} 支撑。
 */
@Mapper
public interface ApAdminAuditLogMapper extends BaseMapper<ApAdminAuditLog> {
}
