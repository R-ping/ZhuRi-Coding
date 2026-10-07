package com.zhuri.coding.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.admin.pojos.ApAdminAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * 运营账号 Mapper（库：leadnews_user）。
 *
 * <p>查询形态固定为「按 username 取一行」，由 {@code uk_username} 覆盖，无需额外索引。
 */
@Mapper
public interface ApAdminAccountMapper extends BaseMapper<ApAdminAccount> {
}
