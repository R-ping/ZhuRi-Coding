package com.zhuri.coding.user.controller.v1;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.zhuri.coding.common.redis.CacheService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wechat/gzh/using")
@Slf4j
public class WechatGZHLogin {

    /**
     * 公开的兜底 Token 字符串（=改前硬编码值）。保留它是为了：
     * <ul>
     *   <li>可追溯：万一回滚或追查时仍能直接看到原值</li>
     *   <li>可联调：application.yml 未配置时仍可跑通本地用例</li>
     * </ul>
     * <b>生产环境必须通过 WECHAT_GZH_TOKEN 环境变量覆盖，{@link #warnIfTokenMissing()} 会在启动期 WARN 提醒</b>。
     */
    public static final String DEFAULT_TOKEN = "huhudong";

    /**
     * 微信公众号服务器配置中的 Token（需与公众号后台填写的值一致）。
     * 兜底默认值为 {@link #DEFAULT_TOKEN}（与原硬编码一致），生产必须经 WECHAT_GZH_TOKEN 环境变量覆盖。
     */
    @Value("${wechat.gzh.token:" + DEFAULT_TOKEN + "}")
    private String gzhToken;

    @Autowired
    private CacheService cacheService;

    /**
     * 启动期自检：未配置 Token 时按 fail-closed 拒绝所有回调；
     * 若仍为公开兜底值 {@link #DEFAULT_TOKEN}，WARN 提示生产必须经 WECHAT_GZH_TOKEN 环境变量覆盖。
     */
    @PostConstruct
    public void warnIfTokenMissing() {
        if (StrUtil.isBlank(gzhToken)) {
            log.error("wechat.gzh.token 未配置：微信公众平台回调将以 fail-closed 拒绝所有请求。"
                + "请从公众号后台获取 Token 并通过 WECHAT_GZH_TOKEN 环境变量注入。");
            return;
        }
        if (DEFAULT_TOKEN.equals(gzhToken)) {
            log.warn("wechat.gzh.token 仍为公开兜底值 {}：仅本地/演示环境可用，生产环境请通过 WECHAT_GZH_TOKEN 环境变量注入真实 Token。",
                DEFAULT_TOKEN);
        }
    }

    @GetMapping
    public String auth(@RequestParam("signature") String signature, @RequestParam("timestamp") String timestamp,
        @RequestParam("nonce") String nonce, @RequestParam("echostr") String echostr) {
        log.info("========== 收到微信公众号GET验证请求 ==========");
        if (StrUtil.isBlank(echostr) || !checkSignature(signature, timestamp, nonce)) {
            log.warn("微信公众号验证失败，拒绝访问");
            return ""; // 验证失败时返回空字符串
        }
        return echostr;
    }

    /**
     * 当普通微信用户向公众账号发消息时，微信服务器将POST消息的XML数据包到开发者填写的URL上。
     * <p>
     * 微信会在回调 URL 上附带 signature/timestamp/nonce，<b>必须校验通过后才能信任请求体</b>：
     * 否则任何人都可以伪造 XML、把 FromUserName 设成受害者 openid，从而换取该账号的登录 token。
     */
    @PostMapping
    public String using(HttpServletRequest request,
        @RequestParam(value = "signature", required = false) String signature,
        @RequestParam(value = "timestamp", required = false) String timestamp,
        @RequestParam(value = "nonce", required = false) String nonce) {
        log.info("========== 收到微信公众号POST请求 ==========");
        if (!checkSignature(signature, timestamp, nonce)) {
            // 返回 success 让微信不再重试，同时不向伪造方透露任何信息
            log.warn("微信公众号消息签名校验失败，已忽略该请求（疑似伪造回调）");
            return "success";
        }
        String xmlData = readXmlFromRequest(request);
        if (StrUtil.isBlank(xmlData)) {
            log.warn("收到空的微信消息");
            return "success";// 代表接收到消息，但不回复
        }
        WechatMessageDto message = parseXmlToMessage(xmlData);
        if (message == null || !"text".equals(message.getMsgType()) || StrUtil.isBlank(message.getContent())) {
            return "success";
        }
        String content = message.getContent().trim();
        if ("登录".equals(content) || "登陆".equals(content)) {
            try {
                // 6位随机数字，5 分钟内有效，用于换取双 Token
                String token = RandomUtil.randomNumbers(6);
                String redisKey = "wechat:token:" + message.getFromUserName();
                cacheService.setEx(redisKey, token, 5, TimeUnit.MINUTES);
                // 注意：token 与 openid 均属敏感信息，不写入日志
                log.info("已处理公众号登录指令，生成一次性登录 token");
                return buildTextMessage(message.getFromUserName(), message.getToUserName(),
                    "您的token为: " + token + "\n有效期: 5分钟");
            } catch (Exception e) {
                log.error("处理微信消息失败", e);
                return "success";
            }
        }
        return "success";
    }

    private WechatMessageDto parseXmlToMessage(String xmlData) {
        try {
            WechatMessageDto dto = new WechatMessageDto();
            dto.setToUserName(extractXmlValue(xmlData, "ToUserName"));
            dto.setFromUserName(extractXmlValue(xmlData, "FromUserName"));
            dto.setCreateTime(extractXmlValue(xmlData, "CreateTime"));
            dto.setMsgType(extractXmlValue(xmlData, "MsgType"));
            dto.setContent(extractXmlValue(xmlData, "Content"));
            dto.setMsgId(extractXmlValue(xmlData, "MsgId"));
            return dto;
        } catch (Exception e) {
            log.error("解析XML消息失败", e);
            return null;
        }
    }

    private String extractXmlValue(String xml, String tagName) {
        int startTag = xml.indexOf("<" + tagName + ">");
        int endTag = xml.indexOf("</" + tagName + ">");

        if (startTag != -1 && endTag != -1) {
            // 提取标签内容，包含CDATA标记
            String value = xml.substring(startTag + tagName.length() + 2, endTag).trim();
            // 去除CDATA标签: <![CDATA[...]]>
            if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) {
                value = value.substring(9, value.length() - 3);
            }
            return value;
        }
        return null;
    }

    // 注意：回复消息时ToUserName和FromUserName需要互换
    private String buildTextMessage(String toUser, String fromUser, String content) {
        long createTime = System.currentTimeMillis() / 1000;

        return String.format(
            "<xml>" +
                "<ToUserName><![CDATA[%s]]></ToUserName>" +
                "<FromUserName><![CDATA[%s]]></FromUserName>" +
                "<CreateTime>%d</CreateTime>" +
                "<MsgType><![CDATA[text]]></MsgType>" +
                "<Content><![CDATA[%s]]></Content>" +
                "</xml>",
            toUser, fromUser, createTime, content
        );
    }


    /**
     * 校验微信服务器签名（GET 接入验证与 POST 消息推送共用）。
     * <p>
     * 算法按微信官方文档：将 Token、timestamp、nonce 三者字典序排序后拼接，取 SHA1 与 signature 比对。
     * <b>fail-closed</b>：Token 未配置或参数缺失时一律返回 false，避免"漏配即放行"。
     */
    private boolean checkSignature(String signature, String timestamp, String nonce) {
        if (StrUtil.isBlank(gzhToken)) {
            log.warn("微信公众号签名校验失败：wechat.gzh.token 未配置");
            return false;
        }
        if (StrUtil.isBlank(signature) || StrUtil.isBlank(timestamp) || StrUtil.isBlank(nonce)) {
            log.warn("微信公众号签名校验失败：signature/timestamp/nonce 不能为空");
            return false;
        }
        String[] params = {gzhToken, timestamp, nonce};
        Arrays.sort(params);
        String hash = DigestUtils.sha1Hex(String.join("", params));
        // 使用恒定时间比较，防止时序攻击
        return MessageDigest.isEqual(
            hash.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从HttpServletRequest中读取XML数据 类似JavaScript中的raw-body，直接读取原始请求体
     */
    private String readXmlFromRequest(HttpServletRequest request) {
        StringBuilder xmlData = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                xmlData.append(line);
            }
        } catch (IOException e) {
            log.error("读取微信XML消息失败", e);
            return null;
        }
        return xmlData.toString();
    }


    @Data
    static class WechatMessageDto {

        private String ToUserName;
        private String FromUserName;
        private String CreateTime;
        private String MsgType;
        private String Content;
        private String MsgId;
    }
}
