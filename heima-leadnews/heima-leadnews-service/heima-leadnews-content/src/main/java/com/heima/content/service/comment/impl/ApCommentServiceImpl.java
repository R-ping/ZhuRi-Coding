package com.heima.content.service.comment.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.comment.ApCommentLikeMapper;
import com.heima.content.mapper.comment.ApCommentMapper;
import com.heima.content.service.comment.ApCommentService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.comment.dtos.CommentDto;
import com.heima.model.comment.pojos.ApComment;
import com.heima.model.comment.pojos.ApCommentLike;
import com.heima.model.audit.AuditContext;
import com.heima.model.audit.AuditEntityType;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.utils.thread.AppThreadLocalUtil;
import com.heima.model.user.pojos.ApUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ApCommentServiceImpl extends ServiceImpl<ApCommentMapper, ApComment> implements ApCommentService {

    @Autowired
    private ApCommentLikeMapper apCommentLikeMapper;

    @Autowired
    private CommentAuditService commentAuditService;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired(required = false)
    private BehaviorEventBus behaviorEventBus;

    @Override
    public ResponseResult getCommentList(CommentDto dto) {
        if (dto == null || dto.getArticleId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        int page = dto.getPage() != null ? dto.getPage() : 1;
        int size = dto.getSize() != null ? dto.getSize() : 3;

        // 查询一级评论：parentId IS NULL
        LambdaQueryWrapper<ApComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApComment::getArticleId, dto.getArticleId())
               .isNull(ApComment::getParentId)
               .orderByDesc(ApComment::getCreatedTime);

        // 分页
        int offset = (page - 1) * size;
        wrapper.last("LIMIT " + offset + "," + size);

        List<ApComment> topComments = list(wrapper);
        if (topComments.isEmpty()) {
            return ResponseResult.okResult(Collections.emptyList());
        }

        // 查询每个一级评论的子评论（最多2条）
        List<Long> parentIds = topComments.stream().map(ApComment::getId).collect(Collectors.toList());
        LambdaQueryWrapper<ApComment> childWrapper = new LambdaQueryWrapper<>();
        childWrapper.in(ApComment::getParentId, parentIds)
                    .orderByAsc(ApComment::getCreatedTime)
                    .last("LIMIT " + (parentIds.size() * 2)); // 粗略限制

        List<ApComment> allChildren = list(childWrapper);

        // 按 parentId 分组，每组最多2条
        Map<Long, List<ApComment>> childrenMap = new HashMap<>();
        for (ApComment child : allChildren) {
            childrenMap.computeIfAbsent(child.getParentId(), k -> new ArrayList<>()).add(child);
        }
        // 限制每组最多2条
        childrenMap.forEach((parentId, children) -> {
            if (children.size() > 2) {
                childrenMap.put(parentId, children.subList(0, 2));
            }
        });

        // 获取当前用户ID（用于判断点赞状态）
        Integer currentUserId = getCurrentUserId();

        List<Map<String, Object>> result = new ArrayList<>();
        for (ApComment top : topComments) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", top.getId());
            item.put("articleId", top.getArticleId());
            item.put("userId", top.getUserId());
            item.put("userName", top.getUserName());
            item.put("userAvatar", top.getUserAvatar());
            item.put("content", top.getContent());
            item.put("likeCount", top.getLikeCount() != null ? top.getLikeCount() : 0);
            item.put("replyCount", top.getReplyCount() != null ? top.getReplyCount() : 0);
            item.put("createdTime", top.getCreatedTime());
            item.put("liked", isLiked(top.getId(), currentUserId));

            List<ApComment> children = childrenMap.getOrDefault(top.getId(), Collections.emptyList());
            List<Map<String, Object>> childList = children.stream().map(child -> {
                Map<String, Object> cm = new HashMap<>();
                cm.put("id", child.getId());
                cm.put("userId", child.getUserId());
                cm.put("userName", child.getUserName());
                cm.put("userAvatar", child.getUserAvatar());
                cm.put("content", child.getContent());
                cm.put("likeCount", child.getLikeCount() != null ? child.getLikeCount() : 0);
                cm.put("parentId", child.getParentId());
                cm.put("createdTime", child.getCreatedTime());
                cm.put("liked", isLiked(child.getId(), currentUserId));
                return cm;
            }).collect(Collectors.toList());
            item.put("children", childList);
            result.add(item);
        }

        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult addComment(CommentDto dto) {
        if (dto == null || dto.getArticleId() == null || dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请输入评论内容");
        }
        if (dto.getContent().length() > 1000) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "评论内容不能超过1000字");
        }

        ApUser user = getCurrentUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        ApComment comment = new ApComment();
        comment.setArticleId(dto.getArticleId());
        comment.setUserId(user.getId());
        comment.setUserName(user.getNickname() != null ? user.getNickname() : "用户");
        comment.setUserAvatar(user.getImage() != null ? user.getImage() : "");
        comment.setParentId(dto.getParentId());
        comment.setContent(dto.getContent().trim());
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setCreatedTime(new Date());

        save(comment);

        // 如果是二级评论，更新父评论的回复数
        if (dto.getParentId() != null) {
            ApComment parent = getById(dto.getParentId());
            if (parent != null) {
                parent.setReplyCount((parent.getReplyCount() != null ? parent.getReplyCount() : 0) + 1);
                updateById(parent);
            }
        }

        // 异步审核评论（立即展示，后台5-10秒后审核）
        if (comment.getId() != null) {
            try {
                int targetType = dto.getArticleType() != null ? dto.getArticleType() : 1;
                Integer targetUserId = dto.getTargetUserId();
                AuditContext auditContext = new AuditContext(AuditEntityType.COMMENT, comment.getId(), user.getId().longValue());
                auditContext.withTitle("")
                    .withContent(comment.getContent())
                    .withAuthorName(comment.getUserName())
                    .withUserId(user.getId())
                    .withTargetType(targetType)
                    .withTargetId(dto.getArticleId())
                    .withTargetUserId(targetUserId);

                commentAuditService.asyncAuditComment(auditContext);
                log.info("评论已加入异步审核队列, commentId={}, targetType={}", comment.getId(), targetType);
            } catch (Exception e) {
                log.error("触发评论异步审核异常, commentId={}", comment.getId(), e);
            }
        }

        // 跨用户评论文章时，触发行为事件（等级分、通知）
        triggerArticleCommentBehavior(dto.getArticleId(), comment.getId(), comment.getContent(), user);

        Map<String, Object> result = new HashMap<>();
        result.put("id", comment.getId());
        result.put("articleId", comment.getArticleId());
        result.put("userId", comment.getUserId());
        result.put("userName", comment.getUserName() != null ? comment.getUserName() : "");
        result.put("userAvatar", comment.getUserAvatar() != null ? comment.getUserAvatar() : "");
        result.put("parentId", comment.getParentId() != null ? comment.getParentId() : 0);
        result.put("content", comment.getContent() != null ? comment.getContent() : "");
        result.put("likeCount", comment.getLikeCount() != null ? comment.getLikeCount() : 0);
        result.put("replyCount", comment.getReplyCount() != null ? comment.getReplyCount() : 0);
        result.put("createdTime", comment.getCreatedTime());
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult likeComment(CommentDto dto) {
        if (dto == null || dto.getCommentId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApUser user = getCurrentUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        ApComment comment = getById(dto.getCommentId());
        if (comment == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "评论不存在");
        }

        // 检查是否已点赞
        LambdaQueryWrapper<ApCommentLike> likeWrapper = new LambdaQueryWrapper<>();
        likeWrapper.eq(ApCommentLike::getCommentId, dto.getCommentId())
                   .eq(ApCommentLike::getUserId, user.getId());
        ApCommentLike existingLike = apCommentLikeMapper.selectOne(likeWrapper);

        if (existingLike != null) {
            // 已点赞，取消点赞
            apCommentLikeMapper.deleteById(existingLike.getId());
            comment.setLikeCount(Math.max(0, (comment.getLikeCount() != null ? comment.getLikeCount() : 1) - 1));
            updateById(comment);
            return ResponseResult.okResult(Map.of("liked", false, "likeCount", comment.getLikeCount()));
        } else {
            // 点赞
            ApCommentLike like = new ApCommentLike();
            like.setCommentId(dto.getCommentId());
            like.setUserId(user.getId());
            like.setCreatedTime(new Date());
            apCommentLikeMapper.insert(like);
            comment.setLikeCount((comment.getLikeCount() != null ? comment.getLikeCount() : 0) + 1);
            updateById(comment);
            return ResponseResult.okResult(Map.of("liked", true, "likeCount", comment.getLikeCount()));
        }
    }

    @Override
    public ResponseResult getCommentById(Long id) {
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        ApComment comment = getById(id);
        if (comment == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "评论不存在");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", comment.getId());
        result.put("articleId", comment.getArticleId());
        result.put("userId", comment.getUserId());
        result.put("userName", comment.getUserName() != null ? comment.getUserName() : "");
        result.put("userAvatar", comment.getUserAvatar() != null ? comment.getUserAvatar() : "");
        result.put("parentId", comment.getParentId() != null ? comment.getParentId() : 0);
        result.put("content", comment.getContent() != null ? comment.getContent() : "");
        result.put("likeCount", comment.getLikeCount() != null ? comment.getLikeCount() : 0);
        result.put("replyCount", comment.getReplyCount() != null ? comment.getReplyCount() : 0);
        result.put("createdTime", comment.getCreatedTime());
        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult getArticleComments(Long articleId, Long cursor, Integer size) {
        if (articleId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        if (size == null || size <= 0) {
            size = 10;
        }

        // 查询一级评论：article_id = ? AND parent_id IS NULL，按createdTime降序，游标分页
        LambdaQueryWrapper<ApComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApComment::getArticleId, articleId)
               .isNull(ApComment::getParentId)
               .orderByDesc(ApComment::getCreatedTime);

        // 游标分页：WHERE id < cursor
        if (cursor != null && cursor > 0) {
            // 先找到cursor对应的createdTime，用时间戳做游标更准确
            ApComment cursorComment = getById(cursor);
            if (cursorComment != null) {
                wrapper.lt(ApComment::getCreatedTime, cursorComment.getCreatedTime());
            } else {
                wrapper.lt(ApComment::getId, cursor);
            }
        }

        // 多查一条判断是否有更多
        wrapper.last("LIMIT " + (size + 1));

        List<ApComment> topComments = list(wrapper);

        // 判断是否有更多
        boolean hasMore = topComments.size() > size;
        if (hasMore) {
            topComments = topComments.subList(0, size);
        }

        if (topComments.isEmpty()) {
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("list", Collections.emptyList());
            emptyResult.put("cursor", 0);
            emptyResult.put("has_more", false);
            return ResponseResult.okResult(emptyResult);
        }

        // 获取当前用户ID
        Integer currentUserId = getCurrentUserId();

        // 查询每个一级评论的子回复（最多2条）
        List<Long> parentIds = topComments.stream().map(ApComment::getId).collect(Collectors.toList());
        LambdaQueryWrapper<ApComment> childWrapper = new LambdaQueryWrapper<>();
        childWrapper.in(ApComment::getParentId, parentIds)
                    .orderByAsc(ApComment::getCreatedTime);
        List<ApComment> allChildren = list(childWrapper);

        // 按 parentId 分组，每组最多2条
        Map<Long, List<ApComment>> childrenMap = new HashMap<>();
        for (ApComment child : allChildren) {
            childrenMap.computeIfAbsent(child.getParentId(), k -> new ArrayList<>()).add(child);
        }
        childrenMap.forEach((pid, children) -> {
            if (children.size() > 2) {
                childrenMap.put(pid, children.subList(0, 2));
            }
        });

        // 构建返回结果
        List<Map<String, Object>> list = new ArrayList<>();
        for (ApComment top : topComments) {
            Map<String, Object> item = new HashMap<>();
            item.put("commentId", top.getId());
            item.put("content", top.getContent() != null ? top.getContent() : "");
            item.put("diggCount", top.getLikeCount() != null ? top.getLikeCount() : 0);
            item.put("replyCount", top.getReplyCount() != null ? top.getReplyCount() : 0);
            item.put("ctime", top.getCreatedTime());

            // 用户信息
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("userName", top.getUserName() != null ? top.getUserName() : "");
            userInfo.put("avatarLarge", top.getUserAvatar() != null ? top.getUserAvatar() : "");
            item.put("userInfo", userInfo);

            // 是否点赞
            item.put("isDigg", isLiked(top.getId(), currentUserId));

            // 子回复列表
            List<ApComment> children = childrenMap.getOrDefault(top.getId(), Collections.emptyList());
            List<Map<String, Object>> replyInfos = children.stream().map(child -> {
                Map<String, Object> reply = new HashMap<>();
                reply.put("commentId", child.getId());
                reply.put("content", child.getContent() != null ? child.getContent() : "");
                reply.put("diggCount", child.getLikeCount() != null ? child.getLikeCount() : 0);
                reply.put("ctime", child.getCreatedTime());

                Map<String, Object> replyUserInfo = new HashMap<>();
                replyUserInfo.put("userName", child.getUserName() != null ? child.getUserName() : "");
                replyUserInfo.put("avatarLarge", child.getUserAvatar() != null ? child.getUserAvatar() : "");
                reply.put("userInfo", replyUserInfo);

                reply.put("isDigg", isLiked(child.getId(), currentUserId));
                return reply;
            }).collect(Collectors.toList());
            item.put("replyInfos", replyInfos);

            list.add(item);
        }

        // 计算下一游标（最后一条评论的ID）
        long nextCursor = topComments.get(topComments.size() - 1).getId();

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("cursor", nextCursor);
        result.put("has_more", hasMore);
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult addArticleComment(Long articleId, String content) {
        if (articleId == null || content == null || content.trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请输入评论内容");
        }
        if (content.length() > 1000) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "评论内容不能超过1000字");
        }

        ApUser user = getCurrentUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        // 创建评论
        ApComment comment = new ApComment();
        comment.setArticleId(articleId);
        comment.setUserId(user.getId());
        comment.setUserName(user.getNickname() != null ? user.getNickname() : "用户");
        comment.setUserAvatar(user.getImage() != null ? user.getImage() : "");
        comment.setParentId(null);
        comment.setRootId(null);
        comment.setContent(content.trim());
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setCreatedTime(new Date());
        save(comment);

        // 更新文章评论数+1
        apArticleMapper.updateCommentCount(articleId, 1);

        // 异步审核评论
        if (comment.getId() != null) {
            try {
                AuditContext auditContext = new AuditContext(AuditEntityType.COMMENT, comment.getId(), user.getId().longValue());
                auditContext.withTitle("")
                    .withContent(comment.getContent())
                    .withAuthorName(comment.getUserName())
                    .withUserId(user.getId())
                    .withTargetType(1)
                    .withTargetId(articleId);
                commentAuditService.asyncAuditComment(auditContext);
                log.info("评论已加入异步审核队列, commentId={}", comment.getId());
            } catch (Exception e) {
                log.error("触发评论异步审核异常, commentId={}", comment.getId(), e);
            }
        }

        // 跨用户评论文章时，触发行为事件（等级分、通知）
        triggerArticleCommentBehavior(articleId, comment.getId(), comment.getContent(), user);

        // 构建返回
        Map<String, Object> result = new HashMap<>();
        result.put("commentId", comment.getId());
        result.put("content", comment.getContent() != null ? comment.getContent() : "");
        result.put("diggCount", 0);
        result.put("replyCount", 0);
        result.put("ctime", comment.getCreatedTime());

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userName", comment.getUserName() != null ? comment.getUserName() : "");
        userInfo.put("avatarLarge", comment.getUserAvatar() != null ? comment.getUserAvatar() : "");
        result.put("userInfo", userInfo);

        result.put("isDigg", false);
        result.put("replyInfos", Collections.emptyList());

        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult replyComment(Long commentId, String content, Long rootId) {
        if (commentId == null || content == null || content.trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请输入回复内容");
        }
        if (content.length() > 1000) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "回复内容不能超过1000字");
        }

        ApUser user = getCurrentUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        // 查找父评论
        ApComment parentComment = getById(commentId);
        if (parentComment == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "被回复的评论不存在");
        }

        // 创建回复
        ApComment reply = new ApComment();
        reply.setArticleId(parentComment.getArticleId());
        reply.setUserId(user.getId());
        reply.setUserName(user.getNickname() != null ? user.getNickname() : "用户");
        reply.setUserAvatar(user.getImage() != null ? user.getImage() : "");
        reply.setParentId(commentId);
        reply.setRootId(rootId);
        reply.setContent(content.trim());
        reply.setLikeCount(0);
        reply.setReplyCount(0);
        reply.setCreatedTime(new Date());
        save(reply);

        // 更新父评论的回复数+1
        parentComment.setReplyCount((parentComment.getReplyCount() != null ? parentComment.getReplyCount() : 0) + 1);
        updateById(parentComment);

        // 跨用户回复评论时，触发行为事件（通知被回复的评论作者）
        triggerArticleCommentBehavior(parentComment.getArticleId(), reply.getId(), reply.getContent(), user);

        // 构建返回
        Map<String, Object> result = new HashMap<>();
        result.put("commentId", reply.getId());
        result.put("content", reply.getContent() != null ? reply.getContent() : "");
        result.put("diggCount", 0);
        result.put("replyCount", 0);
        result.put("ctime", reply.getCreatedTime());

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userName", reply.getUserName() != null ? reply.getUserName() : "");
        userInfo.put("avatarLarge", reply.getUserAvatar() != null ? reply.getUserAvatar() : "");
        result.put("userInfo", userInfo);

        result.put("isDigg", false);

        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult diggComment(Long commentId) {
        if (commentId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApUser user = getCurrentUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        ApComment comment = getById(commentId);
        if (comment == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "评论不存在");
        }

        // 检查是否已点赞
        LambdaQueryWrapper<ApCommentLike> likeWrapper = new LambdaQueryWrapper<>();
        likeWrapper.eq(ApCommentLike::getCommentId, commentId)
                   .eq(ApCommentLike::getUserId, user.getId());
        ApCommentLike existingLike = apCommentLikeMapper.selectOne(likeWrapper);

        if (existingLike != null) {
            // 已点赞，取消点赞
            apCommentLikeMapper.deleteById(existingLike.getId());
            comment.setLikeCount(Math.max(0, (comment.getLikeCount() != null ? comment.getLikeCount() : 1) - 1));
            updateById(comment);
            return ResponseResult.okResult(Map.of("liked", false, "likeCount", comment.getLikeCount()));
        } else {
            // 点赞
            ApCommentLike like = new ApCommentLike();
            like.setCommentId(commentId);
            like.setUserId(user.getId());
            like.setCreatedTime(new Date());
            apCommentLikeMapper.insert(like);
            comment.setLikeCount((comment.getLikeCount() != null ? comment.getLikeCount() : 0) + 1);
            updateById(comment);
            return ResponseResult.okResult(Map.of("liked", true, "likeCount", comment.getLikeCount()));
        }
    }

    private boolean isLiked(Long commentId, Integer userId) {
        if (userId == null) return false;
        LambdaQueryWrapper<ApCommentLike> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApCommentLike::getCommentId, commentId)
               .eq(ApCommentLike::getUserId, userId);
        return apCommentLikeMapper.selectCount(wrapper) > 0;
    }

    /**
     * 跨用户评论文章时，触发 COMMENT_ARTICLE 行为事件（等级分、评论站内信通知）
     * 目标用户为文章作者，自评论（评论者=作者）不触发
     *
     * @param articleId 文章ID
     * @param commentId 评论ID
     * @param content   评论内容
     * @param user      评论者
     */
    private void triggerArticleCommentBehavior(Long articleId, Long commentId, String content, ApUser user) {
        if (behaviorEventBus == null || articleId == null || commentId == null || user == null) {
            return;
        }
        try {
            ApArticle article = apArticleMapper.selectById(articleId);
            if (article == null || article.getAuthorId() == null) {
                return;
            }
            // 自评论不通知
            if (article.getAuthorId().equals(user.getId().longValue())) {
                return;
            }
            BehaviorContext behaviorContext = new BehaviorContext(BehaviorType.COMMENT_ARTICLE, user.getId());
            behaviorContext.withTarget(1, articleId)
                    .withTargetUser(article.getAuthorId().intValue())
                    .withUserInfo(user.getNickname(), user.getImage())
                    .withExtra("commentId", commentId)
                    .withExtra("commentContent", content);
            behaviorEventBus.execute(behaviorContext);
            log.info("文章评论行为事件已触发, articleId={}, fromUser={}, toUser={}",
                    articleId, user.getId(), article.getAuthorId());
        } catch (Exception e) {
            log.error("文章评论行为事件触发失败, articleId={}", articleId, e);
        }
    }

    private Integer getCurrentUserId() {
        try {
            ApUser user = AppThreadLocalUtil.getUser();
            return user != null ? user.getId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private ApUser getCurrentUser() {
        try {
            return AppThreadLocalUtil.getUser();
        } catch (Exception e) {
            return null;
        }
    }
}