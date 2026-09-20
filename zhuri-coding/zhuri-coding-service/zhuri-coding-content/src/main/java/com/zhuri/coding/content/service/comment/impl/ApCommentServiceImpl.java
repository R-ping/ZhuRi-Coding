package com.zhuri.coding.content.service.comment.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.common.com.ImageHandle;
import com.zhuri.coding.content.behavior.service.BehaviorEventBus;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.comment.ApCommentLikeMapper;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
import com.zhuri.coding.content.service.comment.ApCommentService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.audit.AuditContext;
import com.zhuri.coding.model.audit.AuditEntityType;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.comment.dtos.CommentDto;
import com.zhuri.coding.model.comment.dtos.CommentManageDto;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.comment.pojos.ApCommentLike;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        // 查询一级评论：parentId IS NULL（AI 折叠评论 is_hidden=1 不展示）
        LambdaQueryWrapper<ApComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApComment::getArticleId, dto.getArticleId())
               .isNull(ApComment::getParentId)
               .eq(ApComment::getIsHidden, 0)
               .orderByDesc(ApComment::getCreatedTime);

        // 分页
        int offset = (page - 1) * size;
        wrapper.last("LIMIT " + offset + "," + size);

        List<ApComment> topComments = list(wrapper);
        if (topComments.isEmpty()) {
            return ResponseResult.okResult(Collections.emptyList());
        }

        // 查询每个一级评论的子评论（最多2条），折叠回复不展示
        List<Long> parentIds = topComments.stream().map(ApComment::getId).collect(Collectors.toList());
        LambdaQueryWrapper<ApComment> childWrapper = new LambdaQueryWrapper<>();
        childWrapper.in(ApComment::getParentId, parentIds)
                    .eq(ApComment::getIsHidden, 0)
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
            item.put("commentPics", dbPicsToList(top.getCommentPics()));
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
                cm.put("commentPics", dbPicsToList(child.getCommentPics()));
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
        comment.setCommentPics(cleanPicsToDb(dto.getCommentPics()));
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
        result.put("commentPics", dbPicsToList(comment.getCommentPics()));
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
        // 折叠评论仅本人可见（折叠条），禁止继续点赞互动
        if (isHiddenComment(comment)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "该评论已被折叠，无法点赞");
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
        result.put("commentPics", dbPicsToList(comment.getCommentPics()));
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

        // 当前登录用户：决定"折叠仅本人可见"的放行范围（游客一律看不到折叠评论）
        Integer currentUserId = getCurrentUserId();

        // 查询一级评论：article_id = ? AND parent_id IS NULL，按createdTime降序，游标分页。
        // 折叠一级评论（is_hidden=1）对他人整树无痕；仅其作者本人可见折叠条。
        LambdaQueryWrapper<ApComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApComment::getArticleId, articleId)
               .isNull(ApComment::getParentId)
               .orderByDesc(ApComment::getCreatedTime);
        appendHiddenVisible(wrapper, currentUserId);

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

        // 查询每个一级评论的子回复：既包含直接回复(parent_id=一级评论ID)，也包含多级嵌套回复(root_id=一级评论ID)
        // 说明：二级及更深回复通过 root_id 关联到所属一级评论，仅按 parent_id 查询会漏掉“回复的回复”，导致展示不全。
        // 这里按 root_id 或 parent_id 命中即拉取，再按所属一级评论聚合。每页展示10条，更多通过 getCommentReplies 分页加载。
        // 折叠语义：一级折叠整树无痕（本人折叠条不拉子树）；二级折叠仅作者本人可见折叠条、对他人隐藏。
        final int maxRepliesPerTop = 10; // 单条一级评论首屏展示的回复数，更多回复分页加载
        // 仅对"可见"的一级评论拉取子树：折叠评论本人只看到折叠条，不带任何回复
        List<Long> visibleTopIds = topComments.stream()
                .filter(c -> !isHiddenComment(c))
                .map(ApComment::getId)
                .collect(Collectors.toList());
        Set<Long> topIdSet = new HashSet<>(visibleTopIds);
        Map<Long, List<ApComment>> childrenMap = new HashMap<>();
        if (!visibleTopIds.isEmpty()) {
            // 注意：or() 需整体包进 and()，否则后续 eq 过滤会被并入 OR 分支导致条件失效
            LambdaQueryWrapper<ApComment> childWrapper = new LambdaQueryWrapper<>();
            childWrapper.and(w -> w.in(ApComment::getRootId, visibleTopIds)
                        .or(v -> v.in(ApComment::getParentId, visibleTopIds)))
                    .orderByAsc(ApComment::getCreatedTime);
            appendHiddenVisible(childWrapper, currentUserId);
            List<ApComment> allChildren = list(childWrapper);
            for (ApComment child : allChildren) {
                Long groupId = topIdSet.contains(child.getRootId()) ? child.getRootId() : child.getParentId();
                if (groupId == null || !topIdSet.contains(groupId)) {
                    continue;
                }
                List<ApComment> group = childrenMap.computeIfAbsent(groupId, k -> new ArrayList<>());
                if (group.size() < maxRepliesPerTop) {
                    group.add(child);
                }
            }
        }

        // 构建返回结果
        List<Map<String, Object>> list = new ArrayList<>();
        for (ApComment top : topComments) {
            Map<String, Object> item = new HashMap<>();
            item.put("commentId", top.getId());
            item.put("content", top.getContent() != null ? top.getContent() : "");
            item.put("commentPics", dbPicsToList(top.getCommentPics()));
            item.put("diggCount", top.getLikeCount() != null ? top.getLikeCount() : 0);
            item.put("replyCount", top.getReplyCount() != null ? top.getReplyCount() : 0);
            item.put("ctime", top.getCreatedTime());

            // 用户信息
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("userName", top.getUserName() != null ? top.getUserName() : "");
            userInfo.put("avatarLarge", top.getUserAvatar() != null ? top.getUserAvatar() : "");
            item.put("userInfo", userInfo);

            // 是否点赞（折叠评论禁用互动，不查点赞态）
            item.put("isDigg", isHiddenComment(top) ? false : isLiked(top.getId(), currentUserId));
            // 折叠标记：仅作者本人可见自己的折叠评论（他人/游客已在查询层过滤）
            item.put("hidden", isHiddenComment(top));

            // 折叠评论：整树无痕 → 不返回任何回复与"查看更多"入口
            List<ApComment> children = isHiddenComment(top)
                    ? Collections.emptyList()
                    : childrenMap.getOrDefault(top.getId(), Collections.emptyList());
            List<Map<String, Object>> replyInfos = children.stream()
                    .map(child -> buildReplyMap(child, currentUserId))
                    .collect(Collectors.toList());
            item.put("replyInfos", replyInfos);
            // 是否还有更多二级/更深回复（供前端“查看全部N条回复”加载更多）
            boolean hasMoreReplies = !isHiddenComment(top)
                    && top.getReplyCount() != null && top.getReplyCount() > replyInfos.size();
            item.put("hasMoreReplies", hasMoreReplies);

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
    public ResponseResult addArticleComment(Long articleId, String content, List<String> commentPics) {
        boolean hasText = content != null && !content.trim().isEmpty();
        boolean hasPics = commentPics != null && commentPics.stream().anyMatch(p -> p != null && !p.trim().isEmpty());
        if (articleId == null || (!hasText && !hasPics)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请输入评论内容或上传图片");
        }
        if (content != null && content.length() > 1000) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "评论内容不能超过1000字");
        }

        ApUser user = getCurrentUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        // 检查该文章是否开放评论（创作者可在评论管理中关闭/开启）
        ApArticle targetArticle = apArticleMapper.selectById(articleId);
        if (targetArticle != null && Boolean.FALSE.equals(targetArticle.getCommentOpen())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "该文章已关闭评论，无法发表评论");
        }

        // 创建评论
        ApComment comment = new ApComment();
        comment.setArticleId(articleId);
        comment.setUserId(user.getId());
        comment.setUserName(user.getNickname() != null ? user.getNickname() : "用户");
        comment.setUserAvatar(user.getImage() != null ? user.getImage() : "");
        comment.setParentId(null);
        comment.setRootId(null);
        comment.setContent(content != null ? content.trim() : "");
        comment.setCommentPics(cleanPicsToDb(commentPics));
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
        result.put("commentPics", dbPicsToList(comment.getCommentPics()));
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
    public ResponseResult replyComment(Long commentId, String content, Long rootId, List<String> commentPics) {
        boolean hasText = content != null && !content.trim().isEmpty();
        boolean hasPics = commentPics != null && commentPics.stream().anyMatch(p -> p != null && !p.trim().isEmpty());
        if (commentId == null || (!hasText && !hasPics)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请输入回复内容或上传图片");
        }
        if (content != null && content.length() > 1000) {
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
        // 折叠评论仅本人可见（折叠条），禁止在其下继续回复（防争议在已折叠根上生长）
        if (isHiddenComment(parentComment)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "该评论已被折叠，无法回复");
        }

        // 创建回复
        ApComment reply = new ApComment();
        reply.setArticleId(parentComment.getArticleId());
        reply.setUserId(user.getId());
        reply.setUserName(user.getNickname() != null ? user.getNickname() : "用户");
        reply.setUserAvatar(user.getImage() != null ? user.getImage() : "");
        reply.setParentId(commentId);
        reply.setRootId(rootId);
        reply.setContent(content != null ? content.trim() : "");
        reply.setCommentPics(cleanPicsToDb(commentPics));
        reply.setLikeCount(0);
        reply.setReplyCount(0);
        reply.setCreatedTime(new Date());
        save(reply);

        // 更新父评论的回复数+1（直接父级）
        parentComment.setReplyCount((parentComment.getReplyCount() != null ? parentComment.getReplyCount() : 0) + 1);
        updateById(parentComment);

        // 嵌套回复（回复“回复”时）同步累加根一级评论的回复数，确保其 replyCount 包含所有后代回复，供“查看全部回复”分页统计准确
        if (rootId != null && !rootId.equals(commentId)) {
            ApComment rootComment = getById(rootId);
            if (rootComment != null) {
                rootComment.setReplyCount((rootComment.getReplyCount() != null ? rootComment.getReplyCount() : 0) + 1);
                updateById(rootComment);
            }
        }

        // 异步审核回复：与一级评论同一链路（先展示后审核，延迟 5-10 秒）。
        // 红线违规 → 物理删除并通知回复者；温和违规（引战/阴阳/软广）→ is_hidden=1 折叠，
        // 对他人隐藏、仅回复者本人可见折叠条。与 addArticleComment 一致不设 targetUserId，
        // 避免审核回调重复发通知（通知模型缺陷另记，不在此扩大改动）。
        if (reply.getId() != null) {
            try {
                AuditContext auditContext = new AuditContext(AuditEntityType.COMMENT, reply.getId(), user.getId().longValue());
                auditContext.withTitle("")
                    .withContent(reply.getContent())
                    .withAuthorName(reply.getUserName())
                    .withUserId(user.getId())
                    .withTargetType(1)
                    .withTargetId(parentComment.getArticleId());
                commentAuditService.asyncAuditComment(auditContext);
                log.info("回复已加入异步审核队列, replyId={}, rootId={}", reply.getId(), rootId);
            } catch (Exception e) {
                log.error("触发回复异步审核异常, replyId={}", reply.getId(), e);
            }
        }

        // 跨用户回复评论时，触发行为事件（通知被回复的评论作者）
        triggerArticleCommentBehavior(parentComment.getArticleId(), reply.getId(), reply.getContent(), user);

        // 构建返回
        Map<String, Object> result = new HashMap<>();
        result.put("commentId", reply.getId());
        result.put("content", reply.getContent() != null ? reply.getContent() : "");
        result.put("commentPics", dbPicsToList(reply.getCommentPics()));
        result.put("diggCount", 0);
        result.put("replyCount", 0);
        result.put("ctime", reply.getCreatedTime());

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userName", reply.getUserName() != null ? reply.getUserName() : "");
        userInfo.put("avatarLarge", reply.getUserAvatar() != null ? reply.getUserAvatar() : "");
        result.put("userInfo", userInfo);

        result.put("isDigg", false);
        result.put("replyInfos", Collections.emptyList());

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
        // 折叠评论仅本人可见（折叠条），禁止继续点赞互动
        if (isHiddenComment(comment)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "该评论已被折叠，无法点赞");
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

    @Override
    public long countTopComments(Long articleId) {
        if (articleId == null) {
            return 0;
        }
        // 一级评论数（parent_id IS NULL）：与 getArticleComments 列表口径一致，避免与 article.comment 冗余计数漂移不符。
        // AI 折叠评论（is_hidden=1）不计数，保持"展示数=计数"一致
        return count(new LambdaQueryWrapper<ApComment>()
                .eq(ApComment::getArticleId, articleId)
                .isNull(ApComment::getParentId)
                .eq(ApComment::getIsHidden, 0));
    }

    @Override
    public ResponseResult getCommentReplies(Long rootId, Long cursor, Integer size) {
        if (rootId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        if (size == null || size <= 0) {
            size = 10;
        }

        // 折叠一级评论整树无痕：非作者本人不可查看其下任何回复（本人也只见折叠条、无回复展开）
        ApComment root = getById(rootId);
        if (root != null && isHiddenComment(root)) {
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("list", Collections.emptyList());
            emptyResult.put("cursor", 0);
            emptyResult.put("has_more", false);
            return ResponseResult.okResult(emptyResult);
        }

        Integer currentUserId = getCurrentUserId();
        // 二级及更深回复：root_id 指向所属一级评论，按创建时间升序 + 自增ID游标分页（每页10条）。
        // 折叠回复（is_hidden=1）对他人隐藏；仅其作者本人可见折叠条。
        LambdaQueryWrapper<ApComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApComment::getRootId, rootId)
               .orderByAsc(ApComment::getCreatedTime);
        appendHiddenVisible(wrapper, currentUserId);
        if (cursor != null && cursor > 0) {
            wrapper.gt(ApComment::getId, cursor);
        }
        // 多查一条判断是否有更多
        wrapper.last("LIMIT " + (size + 1));

        List<ApComment> replies = list(wrapper);
        boolean hasMore = replies.size() > size;
        if (hasMore) {
            replies = replies.subList(0, size);
        }

        List<Map<String, Object>> list = replies.stream()
                .map(reply -> buildReplyMap(reply, currentUserId))
                .collect(Collectors.toList());

        long nextCursor = replies.isEmpty() ? 0 : replies.get(replies.size() - 1).getId();
        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("cursor", nextCursor);
        result.put("has_more", hasMore);
        return ResponseResult.okResult(result);
    }

    /** 构建单条回复的返回结构（含附带图片）；折叠回复带 hidden 标记供前端渲染"仅自己可见"折叠条 */
    private Map<String, Object> buildReplyMap(ApComment child, Integer currentUserId) {
        Map<String, Object> reply = new HashMap<>();
        reply.put("commentId", child.getId());
        reply.put("content", child.getContent() != null ? child.getContent() : "");
        reply.put("commentPics", dbPicsToList(child.getCommentPics()));
        reply.put("diggCount", child.getLikeCount() != null ? child.getLikeCount() : 0);
        reply.put("ctime", child.getCreatedTime());
        reply.put("hidden", isHiddenComment(child));

        Map<String, Object> replyUserInfo = new HashMap<>();
        replyUserInfo.put("userName", child.getUserName() != null ? child.getUserName() : "");
        replyUserInfo.put("avatarLarge", child.getUserAvatar() != null ? child.getUserAvatar() : "");
        reply.put("userInfo", replyUserInfo);

        reply.put("isDigg", isHiddenComment(child) ? false : isLiked(child.getId(), currentUserId));
        return reply;
    }

    /** 是否 AI 折叠评论（is_hidden=1） */
    private boolean isHiddenComment(ApComment c) {
        return c != null && c.getIsHidden() != null && c.getIsHidden() == 1;
    }

    /**
     * 折叠评论可见性条件：
     * - is_hidden=0（正常）：所有人可见；
     * - is_hidden=1（折叠）：仅其作者本人（登录态）可见折叠条，他人与游客一律不可见。
     * 注意调用需置于包装器其它 AND 条件之后；本方法自带 and() 括号，避免 or 扩散。
     */
    private void appendHiddenVisible(LambdaQueryWrapper<ApComment> w, Integer currentUserId) {
        if (currentUserId == null) {
            w.eq(ApComment::getIsHidden, 0);
            return;
        }
        w.and(x -> x.eq(ApComment::getIsHidden, 0)
                .or(y -> y.eq(ApComment::getIsHidden, 1).eq(ApComment::getUserId, currentUserId)));
    }

    /**
     * 清洗图片URL后转为入库的逗号分隔字符串。
     * URL 通过 OSS 直传会携带签名参数（如 ?x-expires=...&x-signature=...），
     * 需去掉 ? 及其往后的参数，仅保留可长期访问的对象地址。
     */
    private String cleanPicsToDb(List<String> pics) {
        if (pics == null || pics.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String p : pics) {
            if (p == null || p.trim().isEmpty()) {
                continue;
            }
            String c = ImageHandle.handleUrlSuffix(p.trim());
            if (c.isEmpty()) {
                continue;
            }
            if (!cleaned.contains(c)) {
                cleaned.add(c);
            }
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    /** 将入库的逗号分隔图片URL字符串还原为列表 */
    private List<String> dbPicsToList(String pics) {
        List<String> list = new ArrayList<>();
        if (pics == null || pics.trim().isEmpty()) {
            return list;
        }
        for (String s : pics.split(",")) {
            if (!s.trim().isEmpty()) {
                list.add(s.trim());
            }
        }
        return list;
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

    @Override
    public ResponseResult getArticleCommentManageList(CommentManageDto dto) {
        Integer userId = getCurrentUserId();
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        int page = dto.getPage() != null && dto.getPage() > 0 ? dto.getPage() : 1;
        int size = dto.getSize() != null && dto.getSize() > 0 ? dto.getSize() : 10;
        size = Math.min(size, 50);

        // 查询当前用户（作者）名下未删除的文章
        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticle::getAuthorId, userId)
               .eq(ApArticle::getIsDeleted, false)
               .orderByDesc(ApArticle::getPublishTime);
        // 评论开关状态筛选：1-开放 0-关闭
        if (dto.getStatus() != null) {
            wrapper.eq(ApArticle::getCommentOpen, dto.getStatus() == 1);
        }
        // 标题关键字筛选
        if (dto.getKeyword() != null && !dto.getKeyword().trim().isEmpty()) {
            wrapper.like(ApArticle::getTitle, dto.getKeyword().trim());
        }

        Page<ApArticle> p = new Page<>(page, size);
        apArticleMapper.selectPage(p, wrapper);

        // 批量统计每篇文章的评论总数与最新评论时间
        List<Long> articleIds = p.getRecords().stream()
                .map(ApArticle::getId)
                .collect(Collectors.toList());

        Map<Long, Long> commentCountMap = new HashMap<>();
        Map<Long, Date> latestCommentTimeMap = new HashMap<>();
        if (!articleIds.isEmpty()) {
            List<Map<String, Object>> agg = baseMapper.selectMaps(
                    new LambdaQueryWrapper<ApComment>()
                            .select(ApComment::getArticleId, ApComment::getCreatedTime)
                            .in(ApComment::getArticleId, articleIds)
                            .orderByDesc(ApComment::getCreatedTime));
            // 按文章分组统计：出现次数即评论总数，首个元素即最新评论时间
            Map<Long, Long> counter = new HashMap<>();
            Map<Long, Boolean> seen = new HashMap<>();
            for (Map<String, Object> row : agg) {
                Object rawId = row.get("article_id");
                if (rawId == null) continue;
                Long artId = ((Number) rawId).longValue();
                counter.merge(artId, 1L, Long::sum);
                if (!seen.containsKey(artId)) {
                    Object rawTime = row.get("created_time");
                    if (rawTime instanceof Date) latestCommentTimeMap.put(artId, (Date) rawTime);
                    seen.put(artId, Boolean.TRUE);
                }
            }
            commentCountMap.putAll(counter);
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (ApArticle article : p.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", article.getId());
            item.put("title", article.getTitle() != null ? article.getTitle() : "");
            item.put("commentOpen", article.getCommentOpen() != null ? article.getCommentOpen() : true);
            item.put("commentCount", commentCountMap.getOrDefault(article.getId(), 0L));
            item.put("latestCommentTime", latestCommentTimeMap.get(article.getId()));
            item.put("publishTime", article.getPublishTime());
            records.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", records);
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateArticleCommentStatus(Long articleId, Integer status) {
        if (articleId == null || (status == null || (status != 0 && status != 1))) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数无效");
        }
        Integer userId = getCurrentUserId();
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }
        // 仅文章作者可操作评论开关
        if (!userId.equals(article.getAuthorId())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无权操作该文章");
        }

        ApArticle update = new ApArticle();
        update.setId(articleId);
        update.setCommentOpen(status == 1);
        apArticleMapper.updateById(update);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", articleId);
        result.put("commentOpen", status == 1);
        return ResponseResult.okResult(result);
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