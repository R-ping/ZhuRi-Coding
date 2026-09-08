package com.heima.common.aliyun.util;

import com.alibaba.fastjson.JSON;
import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.ImageModerationRequest;
import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.ImageModerationResponseBody;
import com.aliyun.green20220302.models.ImageModerationResponseBody.ImageModerationResponseBodyData;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.heima.common.config.OssConfigForImageScan;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Slf4j
@Component
public class GreenImageScanPlusForOss {

    @Autowired
    private OssConfigForImageScan ossConfig;

    /**
     * 创建请求客户端
     */
    public static Client createClient(String accessKeyId, String accessKeySecret, String endpoint) throws Exception {
        Config config = new Config();
        config.setAccessKeyId(accessKeyId);
        config.setAccessKeySecret(accessKeySecret);
        // 设置http代理。
        //config.setHttpProxy("http://10.10.xx.xx:xxxx");
        // 设置https代理。
        //config.setHttpsProxy("https://10.10.xx.xx:xxxx");
        // 接入区域和地址请根据实际情况修改
        // 接入地址列表：https://help.aliyun.com/document_detail/467828.html?#section-uib-qkw-0c8
        config.setEndpoint(endpoint);
        return new Client(config);
    }

    /**
     * 发起单张图片检测（OBS 版）。
     * <p>objectName 通过方法参数传递，不再使用静态字段，避免多线程并发审核时互相覆盖。
     *
     * @param accessKeyId 阿里云 AccessKey ID
     * @param accessKeySecret 阿里云 AccessKey Secret
     * @param endpoint 内容安全接入端点
     * @param objectName 待检测文件在 OSS 中的 objectName
     * @return 检测响应；异常时返回 null（由调用方决定降级策略）
     */
    private ImageModerationResponse invokeFunction(String accessKeyId, String accessKeySecret, String endpoint,
        String objectName) throws Exception {
        //注意，此处实例化的client请尽可能重复使用，避免重复建立连接，提升检测性能。
        Client client = createClient(accessKeyId, accessKeySecret, endpoint);

        // 创建RuntimeObject实例并设置运行参数
        RuntimeOptions runtime = new RuntimeOptions();

        // 检测参数构造。
        Map<String, String> serviceParameters = new HashMap<>();
        //待检测数据唯一标识
        serviceParameters.put("dataId", UUID.randomUUID().toString());
        // 待检测文件所在bucket的区域
        serviceParameters.put("ossRegionId", resolveRegion());
        // 待检测文件所在bucket名称
        serviceParameters.put("ossBucketName", resolveBucket());
        // 待检测文件
        serviceParameters.put("ossObjectName", objectName);
        ImageModerationRequest request = new ImageModerationRequest();
        // 图片检测service：内容安全控制台图片增强版规则配置的serviceCode，示例：baselineCheck
        // 支持service请参考：https://help.aliyun.com/document_detail/467826.html?0#p-23b-o19-gff
        request.setService("baselineCheck");
        request.setServiceParameters(JSON.toJSONString(serviceParameters));

        ImageModerationResponse response = null;
        try {
            response = client.imageModerationWithOptions(request, runtime);
        } catch (Exception e) {
            log.error("图片审核发生异常，异常信息", e);
        }
        return response;
    }

    public Map imageScan(String url) throws Exception {
        // 时间
        long start = System.currentTimeMillis();
        // 处理url，http(s)://bucketName.endpoint/objectName?Expires=1786286784&OSSAccessKeyId=xxx--->objectName
        String objectName = handleImageUrl2ObjName(url);
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
        String accessKeyId = System.getenv("ALIBABA_RAM_ACCESS_KEY");
        String accessKeySecret = System.getenv("ALIBABA_RAM_ACCESS_SECRET");
        // 接入区域和地址请根据实际情况修改。
        ImageModerationResponse response = invokeFunction(accessKeyId, accessKeySecret, resolveEndpoint(), objectName);
        try {
            // 自动路由。
            if (response != null) {
                //区域切换到cn-beijing。
                if (500 == response.getStatusCode() || (response.getBody() != null && 500 == (response.getBody()
                    .getCode()))) {
                    // 接入区域和地址请根据实际情况修改。
                    response = invokeFunction(accessKeyId, accessKeySecret, resolveEndpoint(), objectName);
                }
            }
            HashMap<String, String> resultMap = new HashMap<>();
            // 打印检测结果。
            if (response != null) {
                if (response.getStatusCode() == 200) {
                    ImageModerationResponseBody body = response.getBody();
                    log.info("图片审核 requestId={}, code={}, msg={}", body.getRequestId(), body.getCode(),
                        body.getMsg());
                    if (body.getCode() == 200) {
                        ImageModerationResponseBodyData data = body.getData();
                        log.info("图片审核响应数据 data={}", JSON.toJSONString(data, true));
                        resultMap.put("level", data.getRiskLevel());
                        return resultMap;
                    } else {
                        log.warn("图片审核未通过 code:{}", body.getCode());
                        return null;
                    }
                } else {
                    log.warn("图片审核响应异常 status:{}", response.getStatusCode());
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("图片审核发生异常，异常信息", e);
        }
        System.out.println("cost time:" + (System.currentTimeMillis() - start));
        return null;
    }

    /**
     * 内容安全接入端点：优先取配置，缺省 green-cip.cn-beijing.aliyuncs.com
     */
    private String resolveEndpoint() {
        return ossConfig != null && ossConfig.getEndpoint() != null && !ossConfig.getEndpoint().isEmpty()
            ? ossConfig.getEndpoint() : "green-cip.cn-beijing.aliyuncs.com";
    }

    /**
     * 待审核图片所在地域：优先取配置，缺省 cn-beijing
     */
    private String resolveRegion() {
        return ossConfig != null && ossConfig.getRegion() != null && !ossConfig.getRegion().isEmpty()
            ? ossConfig.getRegion() : "cn-beijing";
    }

    /**
     * 待审核图片所在 Bucket：优先取配置，缺省 zhuri-leadnews
     */
    private String resolveBucket() {
        return ossConfig != null && ossConfig.getBucket() != null && !ossConfig.getBucket().isEmpty()
            ? ossConfig.getBucket() : "zhuri-leadnews";
    }

    /**
     * OSS 访问域名（不含 bucket 前缀）：优先取配置，缺省 oss-cn-beijing.aliyuncs.com
     */
    private String resolveOssDomain() {
        return ossConfig != null && ossConfig.getOssDomain() != null && !ossConfig.getOssDomain().isEmpty()
            ? ossConfig.getOssDomain() : "oss-cn-beijing.aliyuncs.com";
    }

    @NotNull
    private String handleImageUrl2ObjName(String url) {
        url = url.replace("http://", "").replace("https://", "")
            .replace(resolveBucket(), "").replace(resolveOssDomain(), "")
            .split("\\?")[0].substring(2);
        log.info("url:{}", url);
        return url;
    }

    public static void main(String[] args) throws Exception {
//        Map map = imageScan("https://p6-xtjj-sign.byteimg.com/tos-cn-i-73owjymdk6/255a0b6438bb4e75b3ee7d19098cf807~tplv-73owjymdk6-jj-mark-v1:0:0:0:0:5o6Y6YeR5oqA5pyv56S-5Yy6IEAgTWFjcm9aaGVuZw==:q75.awebp?rk3s=f64ab15b&x-expires=1786623891&x-signature=vibKchsg3T2c0J0JaoCEJxJ7sxY%3D");
//        String url = handleImageUrl2ObjName("https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/material/1786279550620_1t9tyj.jpg?Expires=1786286784&OSSAccessKeyId=TMP.3KuDPsHxtiD4XioLuRvz382KmMmH597GmpkvLiHz5ePgedDLCdvkfNkcojChDqE6LaSuv1y61PnNp12uiS3tJXUMBUfVBS&Signature=kaIHRHYuccoLcQ5GL9htqEmi%2FSQ%3D");
//        System.out.println(url);
//        System.out.println("图片检测结果："+map);
    }

}
