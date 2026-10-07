package com.zhuri.coding.model.admin.dtos;

import lombok.Data;

import java.io.Serializable;

/**
 * 运营动作入参基类（所有需要说明理由的运营操作共用）。
 *
 * <p>{@code reason} 是**必填**字段：运营操作会改变他人内容或账号状态，
 * 事后追溯全靠它。这里刻意不做"默认可空"，让前端必须让运营写下理由。
 * 服务端仍需再校验一次（前端校验只防手误，不防伪造请求）。
 */
@Data
public class AdminActionDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 操作理由（必填；上限 500 字符，与审计表 reason 列等长） */
    private String reason;
}
