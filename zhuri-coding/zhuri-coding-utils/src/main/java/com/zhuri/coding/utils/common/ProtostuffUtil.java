package com.heima.utils.common;


import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProtostuffUtil {

    /**
     * 序列化
     * @param t
     * @param <T>
     * @return
     */
    public static <T> byte[] serialize(T t){
        Schema schema = RuntimeSchema.getSchema(t.getClass());
        return ProtostuffIOUtil.toByteArray(t,schema,
                LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));
 
    }

    /**
     * 反序列化
     * @param bytes
     * @param c
     * @param <T>
     * @return
     */
    public static <T> T deserialize(byte []bytes,Class<T> c) {
        T t = null;
        try {
            t = c.newInstance();
            Schema schema = RuntimeSchema.getSchema(t.getClass());
             ProtostuffIOUtil.mergeFrom(bytes,t,schema);
        } catch (InstantiationException e) {
            log.error("异常信息", e);
        } catch (IllegalAccessException e) {
            log.error("异常信息", e);
        }
        return t;
    }

    /**
     * jdk序列化与protostuff序列化对比
     * @param args
     */
    public static void main(String[] args) {

    }

 
 
}
