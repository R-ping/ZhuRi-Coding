package com.zhuri.coding.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.admin.pojos.ApAdminAccountRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 运营账号角色绑定 Mapper（库：leadnews_user）。
 *
 * <p>查询形态固定为「按 account_id 取全部角色」，由 {@code uk_account_role(account_id, role_code)}
 * 的前缀覆盖，无需额外索引。
 */
@Mapper
public interface ApAdminAccountRoleMapper extends BaseMapper<ApAdminAccountRole> {
}
