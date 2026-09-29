package com.zhuri.coding.content.service.outbox.localmsg;

/**
 * 重放标记：标明「当前线程正在执行一次重放」。
 *
 * <p><b>为什么必须有它</b>：重放时是通过 Spring 容器拿到 <b>代理对象</b> 再反射调方法的，
 * 所以这次调用<b>会再次进入切面</b>。若切面不区分"业务调用"与"重放调用"，
 * 重放会被再次存档而不是真正执行 —— 无限循环，消息永远执行不出去。
 *
 * <p>切面据此分流：重放中 → {@code proceed()} 真正执行；业务调用 → 只存档不执行。
 * 用 ThreadLocal 是因为代理调用发生在同一线程内。
 */
public final class LocalMessageReplayContext {

    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private LocalMessageReplayContext() {
    }

    public static boolean isReplaying() {
        return REPLAYING.get();
    }

    /** 在重放标记内执行一段代码；结束（含异常）后清除标记 */
    public static void runAsReplaying(ThrowingRunnable task) {
        REPLAYING.set(true);
        try {
            task.run();
        } catch (Throwable t) {
            sneakyThrow(t);
        } finally {
            REPLAYING.remove();
        }
    }

    /** 把受检异常按原始类型抛出去（不包一层 RuntimeException，否则 Dispatcher 无法按类型分类失败） */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
