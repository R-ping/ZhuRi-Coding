package com.zhuri.coding.content.service.outbox.localmsg;

import com.zhuri.coding.content.service.outbox.OutboxHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动时扫描所有 bean 上带 {@link LocalMessage} 的方法，建立「事件类型 → 反射重放器」注册表。
 *
 * <p><b>为什么用 BeanPostProcessor 而不是启动后扫容器</b>：每个 bean 初始化完成后顺手检查一次，
 * 不需要为了扫描把所有 bean 提前实例化（破坏懒加载语义）。
 *
 * <p><b>注册表里存的是代理对象</b>，不是目标 bean —— 重放必须再走一次代理，
 * 让「防重入」标记生效、真正执行方法体。
 *
 * <p><b>启动即校验</b>（fail-fast，不带病运行）：
 * 方法必须是 public 且返回 void；同一事件类型只能有一个方法；
 * bean 不能是 JDK 动态代理（接口代理上调不到目标类方法，本项目 Spring Boot 默认 CGLIB，不应出现）。
 */
@Component
public class LocalMessageReplayRegistry implements BeanPostProcessor {

    private final Map<String, OutboxHandler> handlersByType = new HashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof AopInfrastructureBean) {
            return bean;
        }
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        for (Method method : targetClass.getDeclaredMethods()) {
            LocalMessage anno = AnnotatedElementUtils.findMergedAnnotation(method, LocalMessage.class);
            if (anno == null) {
                continue;
            }
            validate(bean, beanName, method, anno);
            register(anno.eventType(),
                    new ReflectiveOutboxHandler(anno.eventType(), bean, method, anno.lifetimeMinutes()),
                    "bean=" + beanName + ", method=" + method.getName());
        }
        return bean;
    }

    private void validate(Object bean, String beanName, Method method, LocalMessage anno) {
        if (Proxy.isProxyClass(bean.getClass())) {
            throw new IllegalStateException(
                    "@LocalMessage 不支持 JDK 动态代理（接口代理上调不到目标类方法）: bean=" + beanName);
        }
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalStateException("@LocalMessage 方法必须为 public: "
                    + targetName(bean, method));
        }
        if (method.getReturnType() != void.class) {
            throw new IllegalStateException("@LocalMessage 方法必须返回 void（重放时返回值无人接收）: "
                    + targetName(bean, method));
        }
        if (!StringUtils.hasText(anno.eventType())) {
            throw new IllegalStateException("@LocalMessage 的 eventType 不能为空: " + targetName(bean, method));
        }
        if (!StringUtils.hasText(anno.key())) {
            throw new IllegalStateException("@LocalMessage 的 key（幂等键 SpEL）不能为空: " + targetName(bean, method));
        }
    }

    private String targetName(Object bean, Method method) {
        return AopUtils.getTargetClass(bean).getSimpleName() + "#" + method.getName();
    }

    /** 供切面与测试注册/查询 */
    public void register(String eventType, OutboxHandler handler, String sourceDescription) {
        OutboxHandler prev = handlersByType.putIfAbsent(eventType, handler);
        if (prev != null) {
            throw new IllegalStateException("重复的 Outbox 事件类型「" + eventType + "」: "
                    + sourceDescription);
        }
    }

    public OutboxHandler forType(String eventType) {
        return handlersByType.get(eventType);
    }

    public Set<String> registeredTypes() {
        return Collections.unmodifiableSet(handlersByType.keySet());
    }
}
