package com.zhuri.coding.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticleManageService;
import com.zhuri.coding.content.service.downlink.ArticleEmbeddingRemover;
import com.zhuri.coding.content.service.downlink.ArticleIndexRemover;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ArticleManageServiceImpl extends ServiceImpl<ApArticleMapper, ApArticle> implements ArticleManageService {

    @Autowired
    private ApArticleContentMapper apArticleContentMapper;

    /** 检索索引清理（事务内登记、提交后送达、失败重试） */
    @Autowired
    private ArticleIndexRemover articleIndexRemover;

    /** 向量清理（同上；索引与向量分属两个外部系统，各走一条投递） */
    @Autowired
    private ArticleEmbeddingRemover articleEmbeddingRemover;

    @Override
    public ResponseResult list(Long authorId, Integer page, Integer size, String status, String title) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = authorId != null ? authorId : user.getId().longValue();
        Page<ApArticle> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticle::getAuthorId, userId);
        wrapper.eq(ApArticle::getIsDeleted, false);
        if (status != null && !status.isEmpty()) {
            Byte statusCode = getStatusCode(status);
            if (statusCode != null) {
                wrapper.eq(ApArticle::getStatus, statusCode);
            }
        }
        if (title != null && !title.isEmpty()) {
            wrapper.like(ApArticle::getTitle, title);
        }
        wrapper.orderByDesc(ApArticle::getCreatedTime);
        IPage<ApArticle> result = page(pageParam, wrapper);
        Map<String, Object> data = new HashMap<>();
        data.put("list", result.getRecords().stream().map(ApArticle::nullSafeToMap).collect(Collectors.toList()));
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult statistics(Long authorId) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = authorId != null ? authorId : user.getId().longValue();
        Map<String, Object> data = new HashMap<>();
        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticle::getAuthorId, userId);
        wrapper.eq(ApArticle::getIsDeleted, false);
        data.put("total", count(wrapper));
        wrapper.eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode());
        data.put("published", count(wrapper));
        wrapper.clear();
        wrapper.eq(ApArticle::getAuthorId, userId);
        wrapper.eq(ApArticle::getIsDeleted, false);
        wrapper.eq(ApArticle::getStatus, ApArticle.Status.SUBMIT.getCode());
        data.put("reviewing", count(wrapper));
        wrapper.clear();
        wrapper.eq(ApArticle::getAuthorId, userId);
        wrapper.eq(ApArticle::getIsDeleted, false);
        wrapper.eq(ApArticle::getStatus, ApArticle.Status.FAIL.getCode());
        data.put("rejected", count(wrapper));
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult deleteArticle(Long id) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "id 不能为空");
        }
        // 归属校验与删除合成一条条件更新：只写 is_deleted 一列，
        // 既不把整行旧快照盖回去（与审核链的状态更新并发时不会互相覆盖），也不必先查再判。
        int affected = baseMapper.softDeleteByAuthor(id, user.getId().longValue());
        if (affected == 0) {
            // 不存在、不是本人、已删除三种情况对外同一句：不泄漏别人的文章是否存在
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        // 删除只改了 MySQL 的一列，索引与向量还在外部系统里。两处各走一条可靠投递：
        // 事务提交后才真正执行，失败重试；对从未发布过的文章是无害空操作（删不存在的东西不报错）。
        articleIndexRemover.removeFromIndex(id);
        articleEmbeddingRemover.remove(id);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult getArticleById(Long id) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "id 不能为空");
        }
        ApArticle article = getById(id);
        // 已删除的文章对作者本人也不可见：否则编辑页能把它读出来、再保存一次就"复活"了
        if (article == null || article.isDeletedArticle()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        // 查询文章内容
        LambdaQueryWrapper<ApArticleContent> contentWrapper = new LambdaQueryWrapper<>();
        contentWrapper.eq(ApArticleContent::getArticleId, id);
        ApArticleContent articleContent = apArticleContentMapper.selectOne(contentWrapper);
        String content = articleContent != null ? articleContent.getContent() : "";

        // 构建返回数据（字段名与前端期望一致）
        Map<String, Object> data = new HashMap<>();
        data.put("id", article.getId());
        data.put("title", article.getTitle() != null ? article.getTitle() : "");
        data.put("authorId", article.getAuthorId() != null ? article.getAuthorId() : "");
        data.put("authorName", article.getAuthorName() != null ? article.getAuthorName() : "");
        data.put("channel_id", article.getChannelId() != null ? article.getChannelId() : "");
        data.put("channelId", article.getChannelId() != null ? article.getChannelId() : "");
        data.put("channel_name", article.getChannelName() != null ? article.getChannelName() : "");
        data.put("channelName", article.getChannelName() != null ? article.getChannelName() : "");
        data.put("layout", article.getLayout() != null ? article.getLayout() : "");
        data.put("cover_image", article.getCoverImage() != null ? article.getCoverImage() : "");
        data.put("coverImage", article.getCoverImage() != null ? article.getCoverImage() : "");
        data.put("columnId", article.getColumnId() != null ? article.getColumnId() : "");
        data.put("labels", article.getTags() != null ? String.join(",", article.getTags()) : "");
        data.put("tags", article.getTags() != null ? article.getTags() : "");
        data.put("content", content);
        data.put("summary", "");
        data.put("publish_time", article.getPublishTime() != null ? article.getPublishTime() : "");
        data.put("publishTime", article.getPublishTime() != null ? article.getPublishTime() : "");
        data.put("status", article.getStatus() != null ? article.getStatus() : "");
        data.put("reason", article.getReason() != null ? article.getReason() : "");
        data.put("createdTime", article.getCreatedTime() != null ? article.getCreatedTime() : "");
        data.put("images", "");
        data.put("type", "0");
        data.put("topic", "");
        return ResponseResult.okResult(data);
    }

    private Byte getStatusCode(String status) {
        switch (status) {
            case "published":
                return ApArticle.Status.PUBLISHED.getCode();
            case "reviewing":
                return ApArticle.Status.SUBMIT.getCode();
            case "rejected":
                return ApArticle.Status.FAIL.getCode();
            default:
                return null;
        }
    }
}