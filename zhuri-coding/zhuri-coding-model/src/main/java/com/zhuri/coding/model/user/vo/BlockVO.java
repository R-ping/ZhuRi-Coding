package com.heima.model.user.vo;

import lombok.Data;
import java.util.Date;

@Data
public class BlockVO {
    private Long id;
    private Integer targetType; // 1-作者, 2-标签
    private Long targetId;
    private String targetName;   // 作者昵称 或 标签名
    private String targetAvatar; // 作者头像
    private Date createTime;
}