package com.heima.content.service.order;

import com.heima.model.course.pojos.ApCourseOrder;
import com.heima.model.common.dtos.ResponseResult;

public interface OrderService {

    /**
     * 创建订单。
     *
     * @param courseId        课程ID
     * @param discountCode    课程专属折扣码（可空）
     * @param couponItemCode  抽奖获得的通用5折券道具代码（如 course50，可空；与折扣码二选一）
     * @param userId          用户ID
     * @param payType         支付方式
     */
    ResponseResult createOrder(Long courseId, String discountCode, String couponItemCode, Long userId, String payType);

    /**
     * 查询订单状态。
     * <p>仅返回当前登录用户自己名下订单，防止越权查看他人订单。
     *
     * @param orderNo 订单号
     * @param userId  当前登录用户 ID（用于归属校验）
     */
    ResponseResult getOrderStatus(String orderNo, Long userId);

    /** 我的订单列表 */
    ResponseResult getMyOrders(Long userId, Integer page, Integer size);

    /**
     * 支付成功回调处理。
     * <p>用条件更新将 {@code PENDING/PROCESSING} 原子置为 {@code PAID} 并完成放权/发奖；
     * 若订单已非待支付/支付处理中态（如并发下已被关单为 CANCELLED、或已 PAID/REFUNDED），
     * 则不做任何后续返回 {@code false}，由调用方决定是否触发退款兜底。
     *
     * @return 是否已将本笔支付真正应用到订单（即置为 PAID 并发放课程/权益）
     */
    boolean handlePaySuccess(String orderNo, String tradeNo);

    /**
     * 超时关单：将待支付订单置为已取消。
     * <p>使用条件更新（WHERE status=PENDING）保证幂等——已支付/已取消的订单不受影响，
     * 由延迟队列消费者在订单创建后到达超时时间时触发。
     *
     * @param orderNo 订单号
     */
    void closeExpiredOrder(String orderNo);

    /**
     * 去支付前的准备（原子抢占 PROCESSING + 核验优惠）：
     * <ul>
     *   <li>仅 {@code PENDING} 状态可被抢占为 {@code PROCESSING}；</li>
     *   <li>若关单已先行（PENDING→CANCELLED）或订单已支付，则抢占失败并返回提示，不跳转支付页；</li>
     *   <li>核验折扣码/通用5折券仍有效，失效则拒绝发起支付；</li>
     *   <li>抢占成功后排程「支付通道超时」关单，避免用户在支付页长时间滞留。</li>
     * </ul>
     *
     * @param orderNo 订单号
     * @param userId  当前用户ID；公开访问（如支付页直连）可传 null 跳过归属/券码核验
     * @return 成功返回订单（data 为 PROCESSING 状态订单）；失败返回错误提示
     */
    ResponseResult preparePay(String orderNo, Long userId);

    /**
     * 关闭支付通道：将处于 {@code PROCESSING}（支付处理中）的订单置为已取消。
     * <p>条件更新（WHERE status=PROCESSING）保证幂等——已支付/已关闭的订单不受影响，
     * 由「去支付」抢占成功后排程的支付通道超时消费者触发。
     *
     * @param orderNo 订单号
     */
    void closePayChannel(String orderNo);

    /**
     * 退款兜底：将因「支付成功但订单已关闭」触发自动退款后的订单置为已退款。
     * <p>条件更新（WHERE status=CANCELLED）保证幂等，杜绝重复退款标记；
     * 由支付宝回调在检测到「钱到账但订单已关」并退款成功后调用。同时清空待重试标记。</p>
     *
     * @param orderNo       订单号
     * @param refundTradeNo 退款流水号
     */
    void markRefunded(String orderNo, String refundTradeNo);

    /**
     * 标记退款待重试：首次自动退款失败时调用，将订单（保持 CANCELLED）的 refund_pending 置 1、
     * refund_retry_count 置 1，由定时任务 {@code RefundRetryTask} 扫描重试。
     * <p>仅当尚未处于待重试状态时生效，避免重复回调把重试计数灌高。</p>
     *
     * @param orderNo 订单号
     */
    void markRefundPending(String orderNo);

    /**
     * 记录一次退款重试失败：将 refund_retry_count +1；当累计达到 {@code maxRetries} 时
     * 把 refund_pending 置 2（已告警、停止自动重试，需人工介入）。
     *
     * @param orderNo   订单号
     * @param maxRetries 重试上限
     * @return true=已达上限触发告警并停止重试；false=未达上限，下轮继续
     */
    boolean markRefundRetryFailure(String orderNo, int maxRetries);

    /** 查询处于「已取消且待退款重试」的订单，供退款重试定时任务使用 */
    java.util.List<ApCourseOrder> listPendingRefundOrders(int limit);

    /** 查询「已达退款重试上限并告警、需人工介入」的订单，供运维/监控使用 */
    java.util.List<ApCourseOrder> listAlertedRefundOrders(int limit);

    /** 根据订单号查询 */
    ApCourseOrder getByOrderNo(String orderNo);
}