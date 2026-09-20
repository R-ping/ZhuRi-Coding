package com.zhuri.coding.user.controller.v1;

import com.zhuri.coding.common.redis.CacheService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
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
import java.lang.reflect.Field;
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
 * - POST using：<b>签名校验不通过（伪造回调）绝不生成登录 token</b>；空消息不回复；
 *   文本"登录"生成 token 并回 XML；非文本/其它内容不生成 token；
 * - 读取请求体 IO 异常时返回"success"；Token 未配置时 fail-closed。
 *
 * 安全背景：POST 处理器历史上未做签名校验，任何人都能伪造 XML 把 FromUserName 设成
 * 受害者 openid 换取登录 token（账号接管），故对伪造回调的断言是本测试的重点。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WechatGZHLogin 微信公众号接入")
class WechatGZHLoginTest {

    /** 测试用公众号 Token（生产由 WECHAT_GZH_TOKEN 环境变量注入） */
    private static final String TOKEN = "test-gzh-token";

    private static final String TIMESTAMP = "1690000000";
    private static final String NONCE = "abc123";

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private WechatGZHLogin wechatGZHLogin;

    @BeforeEach
    void setUp() {
        setGzhToken(TOKEN);
    }

    /** 注入 @Value 字段（无 Spring 上下文时用反射设置） */
    private void setGzhToken(String token) {
        try {
            Field field = WechatGZHLogin.class.getDeclaredField("gzhToken");
            field.setAccessible(true);
            field.set(wechatGZHLogin, token);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入测试 Token 失败", e);
        }
    }

    /** 按微信官方算法计算签名：token/timestamp/nonce 字典序排序后拼接取 SHA1 */
    private String signature(String timestamp, String nonce) {
        return signature(TOKEN, timestamp, nonce);
    }

    /** 指定 token 的签名计算重载（用于验证「兜底默认 Token」仍能验签通过的路径） */
    private String signature(String token, String timestamp, String nonce) {
        String[] arr = {token, timestamp, nonce};
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
            assertEquals("hello", wechatGZHLogin.auth(signature(TIMESTAMP, NONCE), TIMESTAMP, NONCE, "hello"));
        }

        @Test
        @DisplayName("参数缺失 → 返回空串")
        void testAuthNullParam() {
            assertEquals("", wechatGZHLogin.auth(null, "ts", "nonce", "echostr"));
            assertEquals("", wechatGZHLogin.auth("sig", null, "nonce", "echostr"));
            assertEquals("", wechatGZHLogin.auth("sig", "ts", "nonce", null));
        }

        @Test
        @DisplayName("签名不匹配 → 返回空串")
        void testAuthBadSignature() {
            assertEquals("", wechatGZHLogin.auth("wrong-signature", TIMESTAMP, NONCE, "hello"));
        }

        @Test
        @DisplayName("Token 未配置 → 返回空串（fail-closed）")
        void testAuthRejectedWhenTokenMissing() {
            setGzhToken("");
            assertEquals("", wechatGZHLogin.auth(signature(TIMESTAMP, NONCE), TIMESTAMP, NONCE, "hello"));
        }

        @Test
        @DisplayName("Token 为公开兜底值（=原硬编码）→ 合法签名仍可验签（保留可追溯）")
        void testAuthOkWithDefaultToken() {
            // 关键：保留原值作为兜底，本地/演示与回滚场景下仍能跑通；
            // 启动期 PostConstruct 会 WARN 提示生产必须用环境变量覆盖。
            setGzhToken(WechatGZHLogin.DEFAULT_TOKEN);
            String sig = signature(WechatGZHLogin.DEFAULT_TOKEN, TIMESTAMP, NONCE);
            assertEquals("hello", wechatGZHLogin.auth(sig, TIMESTAMP, NONCE, "hello"));
        }

        @Test
        @DisplayName("Token 为公开兜底值 + 错签名 → 仍拒绝")
        void testAuthBadSignatureWithDefaultToken() {
            setGzhToken(WechatGZHLogin.DEFAULT_TOKEN);
            assertEquals("", wechatGZHLogin.auth("wrong-signature", TIMESTAMP, NONCE, "hello"));
        }
    }

    @Nested
    @DisplayName("POST using 消息处理")
    class Using {

        @Test
        @DisplayName("伪造回调（缺少签名）→ 不生成 token（防账号接管）")
        void testForgedWithoutSignature() {
            // 签名校验在读取请求体之前完成，故此处不需要桩 getInputStream
            String result = wechatGZHLogin.using(mock(HttpServletRequest.class), null, TIMESTAMP, NONCE);

            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }

        @Test
        @DisplayName("伪造回调（错误签名）→ 不生成 token（防账号接管）")
        void testForgedWithWrongSignature() {
            String result = wechatGZHLogin.using(
                mock(HttpServletRequest.class), "forged-signature", TIMESTAMP, NONCE);

            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }

        @Test
        @DisplayName("Token 未配置 → 合法签名也不放行（fail-closed）")
        void testRejectedWhenTokenMissing() {
            setGzhToken("");

            String result = wechatGZHLogin.using(
                mock(HttpServletRequest.class), signature(TIMESTAMP, NONCE), TIMESTAMP, NONCE);

            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }

        @Test
        @DisplayName("Token 为公开兜底值 → 合法签名可生成 token（保留可追溯）")
        void testPostOkWithDefaultToken() {
            setGzhToken(WechatGZHLogin.DEFAULT_TOKEN);

            HttpServletRequest req = mockRequestWithXml(textXml("user1", "公众号", "登录"));
            String sig = signature(WechatGZHLogin.DEFAULT_TOKEN, TIMESTAMP, NONCE);
            String result = wechatGZHLogin.using(req, sig, TIMESTAMP, NONCE);

            assertTrue(result.contains("<MsgType><![CDATA[text]]></MsgType>"));
            assertTrue(result.contains("您的token为"));
            verify(cacheService).setEx(eq("wechat:token:user1"), anyString(), eq(5L), eq(TimeUnit.MINUTES));
        }

        @Test
        @DisplayName("Token 为公开兜底值 + 伪造签名 → 仍不生成 token（兜底≠放行）")
        void testPostForgedWithDefaultToken() {
            setGzhToken(WechatGZHLogin.DEFAULT_TOKEN);

            String result = wechatGZHLogin.using(
                mock(HttpServletRequest.class), "forged-signature", TIMESTAMP, NONCE);

            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }

        @Test
        @DisplayName("请求体为空 → 返回 success 且不生成 token")
        void testEmptyBody() {
            String result = wechatGZHLogin.using(
                mockRequestWithXml("  "), signature(TIMESTAMP, NONCE), TIMESTAMP, NONCE);

            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }

        @Test
        @DisplayName("读取请求体失败 → 返回 success")
        void testReadException() {
            String result = wechatGZHLogin.using(
                mockRequestWithXml(null), signature(TIMESTAMP, NONCE), TIMESTAMP, NONCE);

            assertEquals("success", result);
        }

        @Test
        @DisplayName("文本'登录' → 生成 token 缓存并返回 XML")
        void testLoginText() {
            HttpServletRequest req = mockRequestWithXml(textXml("user1", "公众号", "登录"));

            String result = wechatGZHLogin.using(req, signature(TIMESTAMP, NONCE), TIMESTAMP, NONCE);

            assertTrue(result.contains("<MsgType><![CDATA[text]]></MsgType>"));
            assertTrue(result.contains("您的token为"));
            verify(cacheService).setEx(eq("wechat:token:user1"), anyString(), eq(5L), eq(TimeUnit.MINUTES));
        }

        @Test
        @DisplayName("非文本消息 → 返回 success 且不生成 token")
        void testNonText() {
            String result = wechatGZHLogin.using(mockRequestWithXml(
                    "<xml><MsgType><![CDATA[image]]></MsgType></xml>"),
                    signature(TIMESTAMP, NONCE), TIMESTAMP, NONCE);

            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }

        @Test
        @DisplayName("文本但内容不是登录 → 返回 success 且不生成 token")
        void testOtherText() {
            String result = wechatGZHLogin.using(
                mockRequestWithXml(textXml("user1", "公众号", "你好")),
                signature(TIMESTAMP, NONCE), TIMESTAMP, NONCE);

            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }

        @Test
        @DisplayName("文本消息缺少 Content → 返回 success 且不抛异常")
        void testTextWithoutContent() {
            String result = wechatGZHLogin.using(mockRequestWithXml(
                    "<xml><MsgType><![CDATA[text]]></MsgType><FromUserName><![CDATA[u1]]></FromUserName></xml>"),
                    signature(TIMESTAMP, NONCE), TIMESTAMP, NONCE);

            assertEquals("success", result);
            verifyNoInteractions(cacheService);
        }
    }
}
