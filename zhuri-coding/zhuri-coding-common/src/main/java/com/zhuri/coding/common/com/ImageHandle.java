package com.heima.common.com;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ImageHandle {

    /**
     * 处理图片url后缀，去掉?后缀参数
     * @param url
     * @return
     */
    public static String handleUrlSuffix(String url) {
        if (url != null && url.indexOf("?") > 0) {
            return url.substring(0, url.indexOf("?"));
        }
        return url;
    }

    public static List<String> handleUrlSuffix(List<String> url) {
        List<String> urlNotSuffix = url.stream().map(ImageHandle::handleUrlSuffix).collect(Collectors.toList());
        return urlNotSuffix;
    }

}
