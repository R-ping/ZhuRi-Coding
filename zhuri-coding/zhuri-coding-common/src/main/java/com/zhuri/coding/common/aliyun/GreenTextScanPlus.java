package com.heima.common.aliyun;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationPlusRequest;
import com.aliyun.green20220302.models.TextModerationPlusResponse;
import com.aliyun.green20220302.models.TextModerationPlusResponseBody;
import com.aliyun.teaopenapi.models.Config;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GreenTextScanPlus {

    public Map greeTextScan(String content) throws Exception {
        long start = System.currentTimeMillis();
        Config config = new Config();
        /**
         * 阿里云账号AccessKey拥有所有API的访问权限，建议您使用RAM用户进行API访问或日常运维。
         * 常见获取环境变量方式：
         * 方式一：
         *     获取RAM用户AccessKey ID：System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID");
         *     获取RAM用户AccessKey Secret：System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET");
         * 方式二：
         *     获取RAM用户AccessKey ID：System.getProperty("ALIBABA_CLOUD_ACCESS_KEY_ID");
         *     获取RAM用户AccessKey Secret：System.getProperty("ALIBABA_CLOUD_ACCESS_KEY_SECRET");
         */
        String accessId = System.getenv("ALIBABA_RAM_ACCESS_KEY");
        String accessSecret = System.getenv("ALIBABA_RAM_ACCESS_SECRET");
        System.out.println("accessId:" + accessId + "," + "accessSecret:" + accessSecret);
        config.setAccessKeyId(accessId);
        config.setAccessKeySecret(accessSecret);
        //接入区域和地址请根据实际情况修改
        config.setRegionId("cn-beijing");
        config.setEndpoint("green-cip.cn-beijing.aliyuncs.com");
        //读取时超时时间，单位毫秒（ms）。
        config.setReadTimeout(6000);
        //连接时超时时间，单位毫秒（ms）。
        config.setConnectTimeout(3000);
        //设置http代理。
        //config.setHttpProxy("http://xx.xx.xx.xx:xxxx");
        //设置https代理。
        //config.setHttpsProxy("https://xx.xx.xx.xx:xxxx");
        Client client = new Client(config);

        JSONObject serviceParameters = new JSONObject();
        serviceParameters.put("content", content);

        TextModerationPlusRequest textModerationPlusRequest = new TextModerationPlusRequest();
        // 检测类型
        textModerationPlusRequest.setService("comment_detection_pro");
        textModerationPlusRequest.setServiceParameters(serviceParameters.toJSONString());
        HashMap<String, String> resultMap = new HashMap<>();
        try {
            TextModerationPlusResponse response = client.textModerationPlus(textModerationPlusRequest);
            if (response.getStatusCode() == 200) {
                TextModerationPlusResponseBody result = response.getBody();
                log.info("result json:{}", JSON.toJSONString(result));
                log.info("requestId = {}", result.getRequestId());
                log.info("code = {}", result.getCode());
                log.info("msg = {}", result.getMessage());
                Integer code = result.getCode();
                if (200 == code) {
                    TextModerationPlusResponseBody.TextModerationPlusResponseBodyData data = result.getData();
                    System.out.println(JSON.toJSONString(data, true));
                    resultMap.put("level", data.getRiskLevel());
                    log.info("text moderation cost time:" + (System.currentTimeMillis() - start));
                    return resultMap;
                } else {
                    log.info("text moderation not success. code:" + code);
                    return null;
                }
            } else {
                log.info("response not success. status:" + response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            log.error("text moderation exception", e);
            return null;
        }

    }
}
