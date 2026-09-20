package com.heima.content.service.ai;

import com.heima.model.common.dtos.ResponseResult;

/**
 * 作者 AI 复盘报告（面向创作者的增值服务）
 *
 * <p>聚合作者近 N 天已发布文章的表现（发布量/阅读/点赞/收藏环比），
 * 由 LLM 生成结构化复盘（总览 → 亮点解读 → 待改进 → 下周方向）。
 * 报告按窗口缓存（6h），同窗口重复请求直接返回缓存，控制成本。
 */
public interface CreatorReportService {

    /**
     * 生成复盘报告（缓存命中直接返回 cached=true）
     *
     * @param userId 作者（本人）
     * @param days   统计窗口天数（默认 7，上限 30）
     */
    ResponseResult buildReport(Integer userId, int days);
}
