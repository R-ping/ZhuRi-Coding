package com.zhuri.coding.content.service.outbox.localmsg;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 本地消息表·注解式接入：标在「业务事务提交之后才该真正执行」的业务方法上。
 *
 * <p><b>语义（必须先读懂再使用）</b>：在业务事务内调用被本注解标注的方法，方法体<b>不会立即执行</b> ——
 * 切面只把这次调用（事件类型 + 实参）存进本地消息表，与业务变更同事务提交；
 * 真正执行发生在事务提交后，由 {@code OutboxDispatcher} 反射调回本方法。
 * 所以：调用 = 登记，执行 = 事务提交后的重放。
 *
 * <p><b>使用约束（违反会出静默 bug）</b>：
 * <ul>
 *   <li><b>方法必须是 public 且返回 void</b>——非 public 无法被 AOP 代理稳定拦截；返回值在异步执行时无人接收；</li>
 *   <li><b>必须被其他 bean 通过注入的引用调用</b>——同类内 {@code this.method()} 不走代理，切面不命中，
 *       副作用会当场执行而<b>没有</b>存档（丢消息）；</li>
 *   <li><b>参数必须是可 JSON 序列化的简单值</b>（id、金额、字符串），不要传实体或带循环引用的对象；
 *       Long 会被完整保留，但不要用 JS 思维担心精度——Jackson 按 long 读写；</li>
 *   <li><b>方法体必须可安全重试</b>——重放就是再调一次本方法。</li>
 * </ul>
 *
 * <p><b>失败怎么定性</b>：方法内可抛两类框架异常让 Dispatcher 精确处置——
 * {@code OutboxDispatcher.DeadSignal}（业务上永久失败，直接判死不浪费重试）、
 * {@code RetryWithoutCountingException}（暂时性竞态，退回待处理且不消耗重试配额）；
 * 其他异常按普通失败计次重试。
 *
 * <p><b>存档里有什么</b>：只有事件类型 + 实参数组。<b>没有类名和方法名</b>——
 * 方法绑定在启动时按本注解建立，改方法名只需重新部署，不涉及任何已落库数据。
 * （这是与「把调用上下文整体存档」方案的关键差异：那张表是要长期留存的，
 * 把代码结构写进去等于埋一个"重构即失联"的雷。）
 *
 * @see LocalMessageReplayRegistry
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LocalMessage {

    /**
     * 事件类型：既是 Dispatcher 的路由键，也是指标维度
     */
    String eventType();

    /**
     * 幂等键的 SpEL 表达式（求值结果须为 String），基于本方法实参。
     * <p>用 <b>#a0 / #a1…</b> 按位置引用实参（不依赖编译期参数名）。
     * 例：{@code key = "'article_publish:' + #a0"} → {@code article_publish:123}。
     */
    String key();

    /**
     * 生命周期上限（分钟）：超过仍未完成 → 强制判死；0 = 不限（默认）。
     *
     * <p><b>什么时候必须填</b>：方法体内抛 {@code RetryWithoutCountingException}（不计数重试）时
     * **必须**配一个正数 —— 不计数意味着永远到不了"次数超限"，没有时间兜底就是无限重试。
     *
     * <p><b>什么时候保持 0</b>：方法体只走普通计次失败（失败 5 次自然判死）时保持 0 ——
     * 给"必须完成、或等人工介入"的事件套时限，护栏反而成了故障源。
     */
    int lifetimeMinutes() default 0;

    /**
     * 重试上限（次）：普通计次失败到这个数就进死信；0 = 用全局默认（5）。
     *
     * <p><b>怎么定</b>：看"死信之后有没有自动兜底"。有对账巡检自动补的事件可以设小
     * （少几次重试、快点进死信，由巡检自愈）；死信即人工介入的事件保持默认 ——
     * 重试本身几乎零成本，多一次就少一次人工。
     */
    int maxRetries() default 0;
}
