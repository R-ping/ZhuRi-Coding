package com.zhuri.coding.content.service.article;

import com.zhuri.coding.model.article.pojos.ApArticleDraft;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 已发布文章修订（编辑）服务
 *
 * <p>核心语义：已发布文章（status=PUBLISHED）可提交修订，修订内容走审核，
 * 审核期间线上继续展示旧内容（文章不下线、不从列表消失）；审核通过后新内容替换正文，
 * 若修订幅度达"实质更新"阈值则额外记录最后更新时间与更新说明。</p>
 */
public interface ArticleRevisionService {

    /**
     * 创建或更新修订草稿：文章已有待审修订时更新同一条草稿，否则新建并挂载到文章的待审指针。
     *
     * @param revision 修订内容
     * @return 成功返回草稿实体，失败返回明确错误
     */
    ResponseResult createOrUpdateRevision(ApArticleDraft revision);

    /**
     * 提交修订审核：计算实质更新标记并落库，事务提交后异步触发修订审核。
     *
     * @param articleId 文章ID
     * @return 成功返回待审修订草稿
     */
    ResponseResult submitRevision(Long articleId);

    /**
     * 修订审核通过后生效：用修订正文覆盖线上正文/字段，必要（达实质更新阈值）时刷新更新时间。
     *
     * @param articleId 文章ID
     */
    void applyRevision(Long articleId);

    /**
     * 修订审核驳回：仅放弃本次修订（清空待审指针并删除草稿），线上正文与更新时间保持不变。
     *
     * @param articleId 文章ID
     */
    void rejectRevision(Long articleId);

    /**
     * 查询文章的待审修订草稿。
     *
     * @param articleId 文章ID
     * @return 无待审修订时返回空草稿对象
     */
    ResponseResult getPendingRevision(Long articleId);
}