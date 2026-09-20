package com.heima.model.audit;

/**
 * 图片审核结果值对象
 * 封装 GreenImageScanPlus 的扫描结果，替代裸 Map 类型
 */
public class ImageScanResult {

    /** 风险等级：high/medium/low */
    private String level;

    public ImageScanResult() {}

    public ImageScanResult(String level) {
        this.level = level;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    /**
     * 是否为高风险
     */
    public boolean isHighRisk() {
        return "high".equals(level);
    }

    /**
     * 是否为中风险
     */
    public boolean isMediumRisk() {
        return "medium".equals(level);
    }

    /**
     * 是否通过审核
     */
    public boolean isAllowed() {
        return level == null || "low".equals(level) || "pass".equals(level);
    }
}