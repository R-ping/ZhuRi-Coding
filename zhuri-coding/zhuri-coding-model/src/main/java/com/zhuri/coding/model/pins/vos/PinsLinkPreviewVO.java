package com.heima.model.pins.vos;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 链接预览 VO
 */
@Data
@NoArgsConstructor
public class PinsLinkPreviewVO {

    private String url = "";
    private String domain = "";
    private String title = "";
}