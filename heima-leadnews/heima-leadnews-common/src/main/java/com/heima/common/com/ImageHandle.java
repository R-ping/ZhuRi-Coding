package com.heima.common.com;

import org.springframework.stereotype.Component;

@Component
public class ImageHandle {

    /**
     * 处理图片url后缀，去掉?后缀参数
     * @param url
     * @return
     */
    public String handleUrlSuffix(String url) {
        if (url != null && url.indexOf("?") > 0) {
            return url.substring(0, url.indexOf("?"));
        }
        return url;
    }

}
