package com.heima.common.jackson;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InitJacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        // 注册混淆加密模块
        objectMapper = ConfusionModule.registerModule(objectMapper);
        // 注册全局 null 值转空值模块
        objectMapper.registerModule(nullValueModule());
        return objectMapper;
    }

    /**
     * 全局 null 值转空值模块
     * 将序列化输出中的 null 值按类型自动转换为对应空值
     */
    private Module nullValueModule() {
        SimpleModule module = new SimpleModule("NullValueModule");
        module.setSerializerModifier(new NullValueBeanSerializerModifier());
        return module;
    }

}
