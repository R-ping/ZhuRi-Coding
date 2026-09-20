
package com.heima.content.controller.v1.oss;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.utils.BinaryUtil;
import com.aliyun.oss.model.MatchMode;
import com.aliyun.oss.model.PolicyConditions;
import com.heima.common.annotation.RateLimit;
import com.heima.content.config.OssConfig;
import com.heima.model.common.dtos.ResponseResult;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

@RestController
@RequestMapping("/api/v1/media/oss")
@Slf4j
public class OssController {

    @Autowired
    private OssConfig ossConfig;

    @Autowired
    private OSS ossClient;

    @GetMapping("/post_signature")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 200, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult postSignature() {
        JSONObject data = new JSONObject();
        try {
            long expireEndTime = System.currentTimeMillis() + ossConfig.getExpireTime() * 1000;
            Date expiration = new Date(expireEndTime);
            PolicyConditions policyConds = new PolicyConditions();
            policyConds.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE, 0, 1048576000);
            policyConds.addConditionItem(MatchMode.StartWith, PolicyConditions.COND_KEY, ossConfig.getDir());
            String postPolicy = ossClient.generatePostPolicy(expiration, policyConds);
            byte[] binaryData = postPolicy.getBytes(StandardCharsets.UTF_8);
            String encodedPolicy = BinaryUtil.toBase64String(binaryData);
            String postSignature = ossClient.calculatePostSignature(postPolicy);

            data.put("ossAccessKeyId", ossConfig.getAccessKeyId());
            data.put("policy", encodedPolicy);
            data.put("signature", postSignature);
            data.put("dir", ossConfig.getDir());
            data.put("host", ossConfig.getHost());
        } catch (OSSException oe) {
            log.error("OSS Exception: {}", oe.getErrorMessage());
            return ResponseResult.errorResult(503, "获取上传签名失败");
        } catch (ClientException ce) {
            log.error("Client Exception: {}", ce.getMessage());
            return ResponseResult.errorResult(503, "获取上传签名失败");
        } catch (Exception e) {
            log.error("Unexpected exception: {}", e.getMessage());
            return ResponseResult.errorResult(503, "获取上传签名失败");
        }
        return ResponseResult.okResult(data);
    }

    @GetMapping("/presigned_url")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 500, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 50, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult presignedUrl(@RequestParam("key") String key) {
        JSONObject data = new JSONObject();
        try {
            Date expiration = new Date(System.currentTimeMillis() + 3600 * 1000);
            java.net.URL url = ossClient.generatePresignedUrl(ossConfig.getBucket(), key, expiration);
            data.put("url", url.toString());
        } catch (Exception e) {
            log.error("生成签名URL失败: {}", e.getMessage());
            return ResponseResult.errorResult(503, "生成签名URL失败");
        }
        return ResponseResult.okResult(data);
    }
}
