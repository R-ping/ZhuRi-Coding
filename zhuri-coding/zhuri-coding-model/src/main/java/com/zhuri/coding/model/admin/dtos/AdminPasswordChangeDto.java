package com.zhuri.coding.model.admin.dtos;

import lombok.Data;

import java.io.Serializable;

/**
 * 修改**自己**的口令的入参。
 *
 * <p>刻意不继承 {@code AdminActionDto}：改自己的口令不是"处置他人"，
 * 写操作理由没有意义，硬要一个 reason 只会让人填"1"。
 *
 * <p>{@code oldPassword} 必填 —— 会话被他人短暂接触（共用电脑、忘记退出）时，
 * 这是唯一一道"改口令也需要先证明是我"的门槛。
 */
@Data
public class AdminPasswordChangeDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前口令（明文，仅本次请求内使用） */
    private String oldPassword;

    /** 新口令（明文，服务端 BCrypt 后落库） */
    private String newPassword;
}
