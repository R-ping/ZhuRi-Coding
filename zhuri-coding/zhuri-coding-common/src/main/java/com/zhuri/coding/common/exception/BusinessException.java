package com.zhuri.coding.common.exception;

/**
 * 可携带动态文案的业务异常。
 *
 * <p><b>与 {@link CustomException} 的分工</b>：{@code CustomException} 只能带一个
 * {@link com.zhuri.coding.model.common.enums.AppHttpCodeEnum}，文案是枚举里写死的常量。
 * 而有些业务失败必须把<b>具体原因</b>讲给当事人听 —— 「账号已被封禁」后面还得跟着
 * "因为什么、封到什么时候"，否则连申诉都无从下手。这类文案不可能预先枚举，所以这里额外允许一段 message。
 *
 * <p><b>HTTP 状态码固定 200</b>（见 {@link ExceptionCatch}）：这类失败是"业务结果"而不是"服务出错"。
 * 用 5xx 会污染告警（看着像系统故障），用 4xx 又要为每个业务码维护一套映射，
 * 而本项目的 C 端本来就以「HTTP 200 + 业务码」为主（限流 8001 即此形态）。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务码，与 {@code AppHttpCodeEnum} 取值段保持一致 */
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
