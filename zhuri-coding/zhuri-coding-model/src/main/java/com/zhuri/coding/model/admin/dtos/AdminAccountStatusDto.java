package com.zhuri.coding.model.admin.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 启用/停用运营账号的入参。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminAccountStatusDto extends AdminActionDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 1 启用 / 0 停用 */
    private Integer status;
}
