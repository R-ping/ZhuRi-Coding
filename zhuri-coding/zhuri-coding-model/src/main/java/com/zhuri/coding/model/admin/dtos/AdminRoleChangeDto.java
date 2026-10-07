package com.zhuri.coding.model.admin.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 授予/回收运营角色的入参。
 *
 * <p>角色编码原样透传给服务端，服务端再用 {@code AdminRole.parse} 识别；
 * 认不出的编码一律拒绝（**不静默忽略**）—— 这是写接口，把打错的角色码悄悄吞掉
 * 会让运营以为"授过了"，而实际什么也没发生。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminRoleChangeDto extends AdminActionDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色编码：AUDITOR / OPERATOR / SUPER_ADMIN */
    private String roleCode;
}
