package com.heima.model.audit;

/**
 * 审核实体类型枚举
 * 定义需要进行审核的实体类型
 */
public enum AuditEntityType {

    ARTICLE(1, "文章"),
    PINS(2, "沸点"),
    COLUMN(5, "专栏"),
    COMMENT(6, "评论");

    private final int code;
    private final String description;

    AuditEntityType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static AuditEntityType fromCode(int code) {
        for (AuditEntityType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}