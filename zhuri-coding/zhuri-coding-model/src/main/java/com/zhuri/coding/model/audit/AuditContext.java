package com.heima.model.audit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核上下文 - 承载一次审核请求的所有数据
 */
public class AuditContext {

    /** 实体类型 */
    private AuditEntityType entityType;

    /** 实体ID */
    private Long entityId;

    /** 作者ID */
    private Long authorId;

    /** 作者名称 */
    private String authorName;

    /** 标题（文章/专栏有标题，沸点/评论可为空） */
    private String title;

    /** 文本内容 */
    private String content;

    /** 图片URL列表 */
    private List<String> imageUrls = new ArrayList<>();

    /** 目标类型（评论审核用）：1-文章, 2-沸点 */
    private Integer targetType;

    /** 目标实体ID（评论审核用）：文章/沸点ID */
    private Long targetId;

    /** 目标用户ID（评论审核用）：内容作者ID */
    private Integer targetUserId;

    /** 操作用户ID（评论审核用）：评论者ID */
    private Integer userId;

    /** 扩展数据 */
    private Map<String, Object> extra = new HashMap<>();

    public AuditContext() {}

    public AuditContext(AuditEntityType entityType, Long entityId, Long authorId) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.authorId = authorId;
    }

    // ==================== 链式方法 ====================

    public AuditContext withTitle(String title) {
        this.title = title;
        return this;
    }

    public AuditContext withContent(String content) {
        this.content = content;
        return this;
    }

    public AuditContext withAuthorName(String authorName) {
        this.authorName = authorName;
        return this;
    }

    public AuditContext withImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls != null ? imageUrls : new ArrayList<>();
        return this;
    }

    public AuditContext withImageUrl(String imageUrl) {
        if (imageUrl != null && !imageUrl.isEmpty()) {
            this.imageUrls.add(imageUrl);
        }
        return this;
    }

    public AuditContext withTargetType(Integer targetType) {
        this.targetType = targetType;
        return this;
    }

    public AuditContext withTargetId(Long targetId) {
        this.targetId = targetId;
        return this;
    }

    public AuditContext withTargetUserId(Integer targetUserId) {
        this.targetUserId = targetUserId;
        return this;
    }

    public AuditContext withUserId(Integer userId) {
        this.userId = userId;
        return this;
    }

    public AuditContext withExtra(String key, Object value) {
        this.extra.put(key, value);
        return this;
    }

    public AuditContext withExtra(Map<String, Object> extra) {
        this.extra.putAll(extra);
        return this;
    }

    public String getExtraString(String key) {
        Object val = extra.get(key);
        return val != null ? val.toString() : null;
    }

    public Integer getExtraInt(String key) {
        Object val = extra.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return null;
    }

    public Long getExtraLong(String key) {
        Object val = extra.get(key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return null;
    }

    // ==================== Getters & Setters ====================

    public AuditEntityType getEntityType() {
        return entityType;
    }

    public void setEntityType(AuditEntityType entityType) {
        this.entityType = entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls != null ? imageUrls : new ArrayList<>();
    }

    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    public boolean hasImages() {
        return imageUrls != null && !imageUrls.isEmpty();
    }

    public Integer getTargetType() {
        return targetType;
    }

    public void setTargetType(Integer targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Integer getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(Integer targetUserId) {
        this.targetUserId = targetUserId;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra != null ? extra : new HashMap<>();
    }
}