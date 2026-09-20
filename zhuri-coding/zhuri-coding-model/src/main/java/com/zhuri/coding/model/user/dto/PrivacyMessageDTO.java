package com.heima.model.user.dto;

import lombok.Data;

@Data
public class PrivacyMessageDTO {
    private Integer scope; // 0-所有人, 1-我关注的人, 2-互相关注的人, 3-关闭
}