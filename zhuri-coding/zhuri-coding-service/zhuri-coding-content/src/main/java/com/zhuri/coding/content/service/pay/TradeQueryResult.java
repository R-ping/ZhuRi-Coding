package com.zhuri.coding.content.service.pay;

/**
 * 支付宝交易查询结果（供对账补偿使用）。
 *
 * <p>{@code available=false} 表示查询本身未成功（未配置凭据、网络异常、支付宝返回未知错误码），
 * 调用方必须跳过该订单，不能把"查不到"当成"未支付"。</p>
 *
 * @param available   查询是否得到明确结果
 * @param tradeStatus 支付宝交易状态；查询结果为"交易不存在"时为 {@link #STATUS_NOT_EXIST}
 * @param tradeNo     支付宝交易号（交易不存在时为 null）
 * @param totalAmount 支付宝侧订单金额（交易不存在时为 null）
 */
public record TradeQueryResult(boolean available, String tradeStatus, String tradeNo, String totalAmount) {

    /** 支付宝侧没有这笔交易（尚未创建或早已关闭） */
    public static final String STATUS_NOT_EXIST = "TRADE_NOT_EXIST";

    /** 查询不可用：调用方应跳过本轮，不得推断支付结果 */
    public static TradeQueryResult unknown() {
        return new TradeQueryResult(false, null, null, null);
    }

    /** 支付宝侧确认无此交易 */
    public static TradeQueryResult notExist() {
        return new TradeQueryResult(true, STATUS_NOT_EXIST, null, null);
    }

    /** 查询成功，返回真实交易状态 */
    public static TradeQueryResult of(String tradeStatus, String tradeNo, String totalAmount) {
        return new TradeQueryResult(true, tradeStatus, tradeNo, totalAmount);
    }

    /** 仅 TRADE_SUCCESS / TRADE_FINISHED 才算支付成功（支付宝官方规范） */
    public boolean paid() {
        return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
    }

    /** 等待买家付款：可安全关闭支付宝侧交易 */
    public boolean waitingPay() {
        return "WAIT_BUYER_PAY".equals(tradeStatus);
    }
}
