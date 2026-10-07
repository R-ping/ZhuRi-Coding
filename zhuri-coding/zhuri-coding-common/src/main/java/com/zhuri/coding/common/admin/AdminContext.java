package com.zhuri.coding.common.admin;

/**
 * 请求级运营身份上下文。
 *
 * <p>由 {@link AdminAuthInterceptor} 在 preHandle 写入、afterCompletion 清理，
 * 供审计记录器读取"当前是谁、以什么身份在操作"。
 *
 * <p><b>为什么用 ThreadLocal 而不是 request attribute</b>：与项目既有的
 * {@code AppThreadLocalUtil} 保持一致的写法；且业务层拿身份时不必层层透传
 * HttpServletRequest。前提是运营操作全同步执行——若将来改成异步，
 * 需要像 {@code AppUserTaskDecorator} 那样显式跨线程传递（子线程会拿到 null）。
 */
public final class AdminContext {

    private static final ThreadLocal<AdminIdentity> HOLDER = new ThreadLocal<>();

    private AdminContext() {
    }

    public static void set(AdminIdentity identity) {
        HOLDER.set(identity);
    }

    /** 当前运营身份；不在运营请求线程内时返回 null */
    public static AdminIdentity get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
