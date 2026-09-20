package com.heima.common.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConfusionSerializer extends JsonSerializer<Object> {

    @Override
    public  void serialize(Object value, JsonGenerator jsonGenerator, SerializerProvider serializers) throws IOException {
        try {
            if (value != null) {
                jsonGenerator.writeString(value.toString());
                return;
            }
        }catch (Exception e){
            log.error("异常信息", e);
        }
        serializers.defaultSerializeValue(value, jsonGenerator);
    }
}
