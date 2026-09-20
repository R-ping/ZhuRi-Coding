package com.heima.content.service.article;

import com.heima.model.audit.AuditContext;
import com.heima.model.audit.AuditResult;

/**
 * 统一审核服务接口
 * 采用模板方法模式，定义审核流程骨架
 */
public interface AuditService {

    /**
     * 执行审核
     * @param context 审核上下文
     * @return 审核结果
     */
    AuditResult audit(AuditContext context);
}