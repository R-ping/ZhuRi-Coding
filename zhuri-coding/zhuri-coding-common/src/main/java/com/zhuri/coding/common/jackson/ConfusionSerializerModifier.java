package com.heima.common.jackson;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.heima.model.common.annotation.IdEncrypt;

import java.util.ArrayList;
import java.util.List;

public class ConfusionSerializerModifier extends BeanSerializerModifier {

    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                     BeanDescription beanDesc, List<BeanPropertyWriter> beanProperties) {
        List<BeanPropertyWriter> newWriter = new ArrayList<>();
        for(BeanPropertyWriter writer : beanProperties){
            // 仅对标注了 @IdEncrypt 或者名为 "id" 且为数值类型（Long/Integer）的属性应用混淆序列化
            // 跳过字符串类型的 "id" 属性（如 TocItem.id），避免将普通字符串误混淆
            if(null == writer.getAnnotation(IdEncrypt.class) && !(writer.getName().equalsIgnoreCase("id") && isNumericType(writer.getType()))){
                newWriter.add(writer);
            } else {
                writer.assignSerializer(new ConfusionSerializer());
                newWriter.add(writer);
            }
        }
        return newWriter;
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
