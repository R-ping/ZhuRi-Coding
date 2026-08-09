package com.heima.common.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 全局 null 值序列化修改器
 *
 * 将序列化输出中的所有 null 值按类型自动转换为对应的空值：
 * - 字符串 → ""
 * - 数值类型 → 0
 * - 布尔类型 → false
 * - 数组/集合 → []
 * - Map → {}
 * - 其他对象 → null（保留原值）
 *
 * 与 ConfusionSerializerModifier 协同工作，互不冲突。
 */
public class NullValueBeanSerializerModifier extends BeanSerializerModifier {

    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                     BeanDescription beanDesc,
                                                     List<BeanPropertyWriter> beanProperties) {
        for (BeanPropertyWriter writer : beanProperties) {
            JavaType type = writer.getType();
            JsonSerializer<Object> nullSerializer = createNullSerializer(type);
            if (nullSerializer != null) {
                writer.assignNullSerializer(nullSerializer);
            }
        }
        return beanProperties;
    }

    private JsonSerializer<Object> createNullSerializer(JavaType type) {
        if (type == null) {
            return null;
        }

        // 字符串 → ""
        if (type.isTypeOrSubTypeOf(String.class)) {
            return new JsonSerializer<Object>() {
                @Override
                public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                    gen.writeString("");
                }
            };
        }

        // 数值类型 → 0
        if (type.isPrimitive() || type.isTypeOrSubTypeOf(Number.class)) {
            // boolean 类型不要转 0
            if (!type.isTypeOrSubTypeOf(Boolean.class) && !type.getTypeName().equals("boolean")) {
                return new JsonSerializer<Object>() {
                    @Override
                    public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                        gen.writeNumber(0);
                    }
                };
            }
        }

        // 布尔类型 → false
        if (type.isTypeOrSubTypeOf(Boolean.class) || type.getTypeName().equals("boolean")) {
            return new JsonSerializer<Object>() {
                @Override
                public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                    gen.writeBoolean(false);
                }
            };
        }

        // 数组/集合 → []
        if (type.isArrayType() || type.isCollectionLikeType()) {
            return new JsonSerializer<Object>() {
                @Override
                public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                    gen.writeStartArray();
                    gen.writeEndArray();
                }
            };
        }

        // Map → {}
        if (type.isTypeOrSubTypeOf(Map.class)) {
            return new JsonSerializer<Object>() {
                @Override
                public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                    gen.writeStartObject();
                    gen.writeEndObject();
                }
            };
        }

        return null;
    }
}