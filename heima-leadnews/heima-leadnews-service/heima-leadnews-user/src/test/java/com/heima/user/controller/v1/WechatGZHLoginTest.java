package com.heima.user.controller.v1;

import com.heima.common.redis.CacheService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WechatGZHLogin 单元测试（微信公众号服务器接入）
 *
 * 覆盖：
 * - GET auth：SHA1 签名验证通过回显 echostr / 参数缺失或签名不匹配返回空串；
 * - POST using：空消息不回复、文本"登录"生成 token 并回 XML、非文本/其它内容不生成 token；
 * - 读取请求体 IO 异常时返回"success"。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WechatGZHLogin 微信公众号接入")
class WechatGZHLoginTest {

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private WechatGZHLogin wechatGZHLogin;

    /** 与实现内 TOKEN 一致 */
    private static final String TOKEN = "huhudong";

    private String signature(String timestamp, String nonce) {
        // 字典序排序 TOKEN/timestamp/nonce 后拼接做 sha1
        String[] arr = {TOKEN, timestamp, nonce};
        java.util.Arrays.sort(arr);
        return DigestUtils.sha1Hex(String.join("", arr));
    }

    private String textXml(String from, String to, String content) {
        return "<xml><ToUserName><![CDATA[" + to + "]]></ToUserName>" +
                "<FromUserName><![CDATA[" + from + "]]></FromUserName>" +
                "<CreateTime>1723800000</CreateTime>" +
                "<MsgType><![CDATA[text]]></MsgType>" +
                "<Content><![CDATA[" + content + "]]></Content>" +
                "<MsgId>123</MsgId></xml>";
    }

    private HttpServletRequest mockRequestWithXml(String xml) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        try {
            if (xml == null) {
                when(request.getInputStream()).thenThrow(new IOException("io down"));
            } else {
                ByteArrayInputStream bais = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
                when(request.getInputStream()).thenReturn(toServletInputStream(bais));
            }
        } catch (IOException ignored) {
            // mock 声明阶段不可能抛出
        }
        return request;
    }

    /** 把普通字节流包装为 ServletInputStream */
    private static ServletInputStream toServletInputStream(InputStream in) {
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return in.read();
            }
            @Override
            public boolean isFinished() {
                return in instanceof ByteArrayInputStream bais && bais.available() == 0;
            }
            @Override
            public boolean isReady() {
                return true;
            }
            @Override
            public void setReadListener(ReadListener readListener) {
                // 无需实现
            }
        };
    }

    @Nested
    @DisplayName("GET auth 签名验证")
    class Auth {

        @Test
        @DisplayName("签名正确 → 回显 echostr")
        void testAuthOk() {
            String ts = "1690000000", nonce = "abc123", echostr = "hello";
            assertEquals("hello", wechatGZHLogin.auth(signature(ts, nonce), ts, nonce, echostr));
        }

        @Test
        @DisplayName("参数缺失 → 返回空串")
        void testAuthNullParam() {
            assertEquals("", wechatGZHLogin.auth(null, "ts", "nonce", "echostr"));
            assertEquals("", wechatGZHLogin.auth("sig", null, "nonce", "echostr"));
        }

        @Test
        @DisplayName("签名不匹配 → 返回空串")
        void testAuthBadSignature() {
            assertEquals("", wechatGZHLogin.auth("wrong-signature", "1690000000", "abc123", "hello"));
        }
    }

    @Nested
    @DisplayName("POST using 消息处理")
    class Using {

        @Test
        @DisplayName("请求体为空 → 返回 success 且不生成 token")
        void testEmptyBody() {
            String result = wechatGZHLogin.using(mockRequestWithXml("  "));
            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }

        @Test
        @DisplayName("读取请求体失败 → 返回 success")
        void testReadException() {
            String result = wechatGZHLogin.using(mockRequestWithXml(null));
            assertEquals("success", result);
        }

        @Test
        @DisplayName("文本'登录' → 生成 token 缓存并返回 XML")
        void testLoginText() {
            HttpServletRequest req = mockRequestWithXml(textXml("user1", "公众号", "登录"));

            String result = wechatGZHLogin.using(req);

            assertTrue(result.contains("<MsgType><![CDATA[text]]></MsgType>"));
            assertTrue(result.contains("您的token为"));
            verify(cacheService).setEx(eq("wechat:token:user1"), anyString(), eq(5L), eq(TimeUnit.MINUTES));
        }

        @Test
        @DisplayName("非文本消息 → 返回 success 且不生成 token")
        void testNonText() {
            String result = wechatGZHLogin.using(mockRequestWithXml(
                    "<xml><MsgType><![CDATA[image]]></MsgType></xml>"));
            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }

        @Test
        @DisplayName("文本但内容不是登录 → 返回 success 且不生成 token")
        void testOtherText() {
            String result = wechatGZHLogin.using(mockRequestWithXml(textXml("user1", "公众号", "你好")));
            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }
    }
}