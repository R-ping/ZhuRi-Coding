package com.heima.content.service.pins.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.apis.notification.INotificationClient;
import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.content.mapper.pins.ApPinsCommentMapper;
import com.heima.content.mapper.pins.ApPinsLikeMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.utils.NotificationHelper;
import com.heima.model.audit.AuditContext;
import com.heima.model.audit.AuditEntityType;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.pins.dtos.PinsCommentDTO;
import com.heima.model.pins.dtos.PinsShareDTO;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.pins.pojos.ApPinsComment;
import com.heima.model.pins.pojos.ApPinsLike;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Component
@Slf4j
public class PinsInteractionService {

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private ApPinsLikeMapper apPinsLikeMapper;

    @Autowired
    private ApPinsCommentMapper apPinsCommentMapper;

    @Autowired(required = false)
    private BehaviorEventBus behaviorEventBus;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Autowired(required = false)
    private PinsCommentAuditService pinsCommentAuditService;

    @Transactional(rollbackFor = Exception.class)
    public ResponseResult like(Long pinsId) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (pinsId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "pinsId不能为空");
        }

        LambdaQueryWrapper<ApPinsLike> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPinsLike::getPinsId, pinsId);
        wrapper.eq(ApPinsLike::getUserId, user.getId());
        ApPinsLike existLike = apPinsLikeMapper.selectOne(wrapper);

        if (existLike != null) {
            return ResponseResult.okResult();
        }

        ApPinsLike like = new ApPinsLike();
        like.setPinsId(pinsId);
        like.setUserId(user.getId());
        like.setCreatedTime(new Date());
        apPinsLikeMapper.insert(like);

        // 更新沸点点赞数
        ApPins pins = apPinsMapper.selectById(pinsId);
        if (pins != null) {
            pins.setLikes((pins.getLikes() != null ? pins.getLikes() : 0) + 1);
            apPinsMapper.updateById(pins);

            // 跨用户点赞时，触发行为事件（等级分、通知）
            if (behaviorEventBus != null && pins.getAuthorId() != null
                    && !pins.getAuthorId().equals(user.getId().longValue())) {
                try {
                    BehaviorContext behaviorContext = new BehaviorContext(BehaviorType.LIKE_PIN, user.getId());
                    behaviorContext.withTarget(2, pinsId)
                            .withTargetUser(pins.getAuthorId().intValue())
                            .withUserInfo(user.getNickname(), user.getImage());
                    behaviorEventBus.execute(behaviorContext);
                    log.info("沸点点赞行为事件已触发, pinsId={}, fromUser={}, toUser={}",
                            pinsId, user.getId(), pins.getAuthorId());
                } catch (Exception e) {
                    log.error("沸点点赞行为事件处理失败, pinsId={}", pinsId, e);
                }
            }
        }
        return ResponseResult.okResult();
    }

    @Transactional(rollbackFor = Exception.class)
    public ResponseResult unlike(Long pinsId) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (pinsId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "pinsId不能为空");
        }

        LambdaQueryWrapper<ApPinsLike> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPinsLike::getPinsId, pinsId);
        wrapper.eq(ApPinsLike::getUserId, user.getId());
        ApPinsLike existLike = apPinsLikeMapper.selectOne(wrapper);

        if (existLike == null) {
            return ResponseResult.okResult();
        }
        apPinsLikeMapper.deleteById(existLike.getId());

        ApPins pins = apPinsMapper.selectById(pinsId);
        if (pins != null) {
            int newLikes = Math.max(0, (pins.getLikes() != null ? pins.getLikes() : 0) - 1);
            pins.setLikes(newLikes);
            apPinsMapper.updateById(pins);
        }
        return ResponseResult.okResult();
    }

    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createComment(PinsCommentDTO dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        log.info("评论人信息：{}",user);
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto.getPinsId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "pinsId不能为空");
        }
        // 评论支持纯文本或纯图片（表情包），两者至少有其一个
        boolean hasContent = dto.getContent() != null && !dto.getContent().trim().isEmpty();
        boolean hasImage = dto.getImageUrls() != null && !dto.getImageUrls().isEmpty();
        if (!hasContent && !hasImage) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "评论内容不能为空");
        }
        // 字数限制：最多 1000 字
        if (hasContent && dto.getContent().trim().length() > 1000) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "评论内容不能超过1000字");
        }

        ApPinsComment comment = new ApPinsComment();
        comment.setPinsId(dto.getPinsId());
        comment.setUserId(user.getId());
        comment.setUserName(user.getNickname() != null ? user.getNickname() : "");
        comment.setUserAvatar(user.getImage() != null ? user.getImage() : "");
        comment.setParentId(dto.getParentId());
        comment.setContent(dto.getContent() != null ? dto.getContent() : "");
        comment.setImageUrls(joinImageUrls(dto.getImageUrls()));
        comment.setReplyToUserId(dto.getReplyToUserId());
        comment.setReplyToUserName(dto.getReplyToUserName() != null ? dto.getReplyToUserName() : "");
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setCreatedTime(new Date());
        apPinsCommentMapper.insert(comment);

        // 更新沸点评论数
        ApPins pins = apPinsMapper.selectById(dto.getPinsId());
        if (pins != null) {
            pins.setComment((pins.getComment() != null ? pins.getComment() : 0) + 1);
            apPinsMapper.updateById(pins);
        }

        // 如果是回复，更新父评论的回复数
        if (dto.getParentId() != null) {
            ApPinsComment parentComment = apPinsCommentMapper.selectById(dto.getParentId());
            if (parentComment != null && parentComment.getIsHidden() != null && parentComment.getIsHidden() == 1) {
                // 折叠评论已全局隐藏，禁止在其下继续回复（防争议在已折叠评论上生长）
                return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "该评论已被折叠，无法回复");
            }
            if (parentComment != null) {
                parentComment.setReplyCount((parentComment.getReplyCount() != null ? parentComment.getReplyCount() : 0) + 1);
                apPinsCommentMapper.updateById(parentComment);
            }
        }

        // 跨用户评论时：向沸点作者发送评论通知，并触发行为事件（等级分）
        if (pins != null && pins.getAuthorId() != null
                && !pins.getAuthorId().equals(user.getId().longValue())) {
            // 沸点评论通知：创建即通知（先展示后审核），审核回调不再补发；
            // 治理后置：红线删除 / 温和折叠由 PinsCommentAuditService 异步执行（见方法末尾入队）。
            NotificationHelper.sendCommentNotification(
                    notificationClient,
                    pins.getAuthorId().intValue(),
                    user.getId(),
                    comment.getContent(),
                    2,
                    dto.getPinsId());
            // 等级分等仍走行为事件总线
            if (behaviorEventBus != null) {
                try {
                    BehaviorContext behaviorContext = new BehaviorContext(BehaviorType.COMMENT_PIN, user.getId());
                    behaviorContext.withTarget(2, dto.getPinsId())
                            .withTargetUser(pins.getAuthorId().intValue())
                            .withUserInfo(user.getNickname(), user.getImage())
                            .withExtra("commentId", comment.getId())
                            .withExtra("commentContent", dto.getContent());
                    behaviorEventBus.execute(behaviorContext);
                    log.info("沸点评论行为事件已触发, pinsId={}, fromUser={}, toUser={}",
                            dto.getPinsId(), user.getId(), pins.getAuthorId());
                } catch (Exception e) {
                    log.error("沸点评论行为事件处理失败, pinsId={}", dto.getPinsId(), e);
                }
            }
        }

        // 异步审核沸点评论（先展示后审核，延迟 5-10 秒）：
        // 红线违规 → 物理删除并通知评论者；温和违规（引战/阴阳/软广）→ is_hidden=1 折叠（全局隐藏，数据保留）。
        // 作者"新评论"通知为创建即发（上方 sendCommentNotification），治理后置，审核回调不再补发通知。
        if (comment.getId() != null && pinsCommentAuditService != null) {
            try {
                AuditContext auditContext = new AuditContext(AuditEntityType.COMMENT, comment.getId(), user.getId().longValue());
                auditContext.withTitle("")
                    .withContent(comment.getContent())
                    .withAuthorName(comment.getUserName())
                    .withUserId(user.getId())
                    .withTargetType(2)
                    .withTargetId(dto.getPinsId());
                pinsCommentAuditService.asyncAuditComment(auditContext);
                log.info("沸点评论已加入异步审核队列, commentId={}, pinsId={}", comment.getId(), dto.getPinsId());
            } catch (Exception e) {
                log.error("触发沸点评论异步审核异常, commentId={}", comment.getId(), e);
            }
        }

        return ResponseResult.okResult(comment);
    }

    @Transactional(rollbackFor = Exception.class)
    public ResponseResult share(PinsShareDTO dto) {
        if (dto.getPinsId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "pinsId不能为空");
        }
        ApPins pins = apPinsMapper.selectById(dto.getPinsId());
        if (pins == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        // 原子递增分享数
        apPinsMapper.incrementShare(dto.getPinsId());
        return ResponseResult.okResult();
    }

    /**
     * 将图片URL列表拼接为逗号分隔字符串，空列表返回空字符串
     */
    private String joinImageUrls(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return "";
        }
        return String.join(",", imageUrls);
    }
}