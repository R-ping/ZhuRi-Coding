package com.heima.common.jackson;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.heima.model.common.annotation.IdEncrypt;

import java.util.Iterator;

public class ConfusionDeserializerModifier extends BeanDeserializerModifier {

    @Override
    public BeanDeserializerBuilder updateBuilder(final DeserializationConfig config, final BeanDescription beanDescription, final BeanDeserializerBuilder builder) {
        Iterator it = builder.getProperties();

        while (it.hasNext()) {
            SettableBeanProperty p = (SettableBeanProperty) it.next();
            // 仅对标注了 @IdEncrypt 或者名为 "id" 且为数值类型（Long/Integer）的属性应用混淆反序列化
            // 跳过字符串类型的 "id" 属性（如 TocItem.id），避免将普通字符串误当作加密 ID 处理
            if (null != p.getAnnotation(IdEncrypt.class) || (p.getName().equalsIgnoreCase("id") && isNumericType(p.getType()))) {
                builder.addOrReplaceProperty(p.withValueDeserializer(new ConfusionDeserializer(p.getValueDeserializer(),p.getType())), true);
            }
        }
        return builder;
    }

    /**
     * 判断 JavaType 是否为数值类型（Long / Integer 及其包装类型）
     */
    private boolean isNumericType(JavaType type) {
        if (type == null) {
            return false;
        }
        String typeName = type.getTypeName();
        return typeName.contains("Long") || typeName.contains("Integer")
            || typeName.contains("long") || typeName.contains("int");
    }
}
