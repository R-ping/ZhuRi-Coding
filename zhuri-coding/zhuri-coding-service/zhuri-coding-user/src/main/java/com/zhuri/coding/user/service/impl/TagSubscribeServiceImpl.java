package com.heima.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.user.pojos.SysTag;
import com.heima.model.user.pojos.UserTagRelation;
import com.heima.model.user.vo.TagDiscoverVO;
import com.heima.user.mapper.SysTagMapper;
import com.heima.user.mapper.UserTagRelationMapper;
import com.heima.user.service.TagSubscribeService;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class TagSubscribeServiceImpl implements TagSubscribeService {

    @Autowired
    private SysTagMapper sysTagMapper;

    @Autowired
    private UserTagRelationMapper userTagRelationMapper;

    @Override
    public ResponseResult discover(String sort, String keyword, Integer page, Integer size) {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        Long userId = currentUser != null ? currentUser.getId().longValue() : null;

        QueryWrapper<SysTag> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like("tag_name", keyword.trim());
        }
        if ("latest".equals(sort)) {
            wrapper.orderByDesc("id");
        } else {
            wrapper.orderByDesc("sort_order");
        }

        Page<SysTag> pageParam = new Page<>(page, size);
        IPage<SysTag> result = sysTagMapper.selectPage(pageParam, wrapper);

        List<TagDiscoverVO> voList = new ArrayList<>();
        for (SysTag tag : result.getRecords()) {
            TagDiscoverVO vo = new TagDiscoverVO();
            vo.setId(tag.getId());
            vo.setTagName(tag.getTagName());
            vo.setArticleCount(tag.getSortOrder() != null ? tag.getSortOrder() : 0);

            // 关注数
            LambdaQueryWrapper<UserTagRelation> countWrapper = new LambdaQueryWrapper<>();
            countWrapper.eq(UserTagRelation::getTagId, tag.getId());
            countWrapper.eq(UserTagRelation::getRelType, 2);
            Long followCount = userTagRelationMapper.selectCount(countWrapper);
            vo.setFollowCount(followCount != null ? followCount.intValue() : 0);

            // 当前用户是否已关注
            if (userId != null) {
                LambdaQueryWrapper<UserTagRelation> followWrapper = new LambdaQueryWrapper<>();
                followWrapper.eq(UserTagRelation::getUserId, userId);
                followWrapper.eq(UserTagRelation::getTagId, tag.getId());
                followWrapper.eq(UserTagRelation::getRelType, 2);
                Long count = userTagRelationMapper.selectCount(followWrapper);
                vo.setIsFollowing(count > 0);
            } else {
                vo.setIsFollowing(false);
            }

            voList.add(vo);
        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("total", result.getTotal());
        resultMap.put("page", page);
        resultMap.put("size", size);
        resultMap.put("list", voList);
        return ResponseResult.okResult(resultMap);
    }

    @Override
    public ResponseResult getFollowed() {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = currentUser.getId().longValue();

        LambdaQueryWrapper<UserTagRelation> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.eq(UserTagRelation::getUserId, userId);
        relationWrapper.eq(UserTagRelation::getRelType, 2);
        List<UserTagRelation> relations = userTagRelationMapper.selectList(relationWrapper);

        // 与 discover 返回同口径（TagDiscoverVO：id/tagName/followCount/articleCount/isFollowing），
        // 保证设置页"全部标签"与"已关注标签"两栏展示的统计一致
        List<TagDiscoverVO> tagList = new ArrayList<>();
        for (UserTagRelation relation : relations) {
            SysTag tag = sysTagMapper.selectById(relation.getTagId());
            if (tag != null) {
                TagDiscoverVO vo = new TagDiscoverVO();
                vo.setId(tag.getId());
                vo.setTagName(tag.getTagName());
                vo.setArticleCount(tag.getSortOrder() != null ? tag.getSortOrder() : 0);
                // 关注数：该标签被多少人关注（rel_type=2）
                LambdaQueryWrapper<UserTagRelation> countWrapper = new LambdaQueryWrapper<>();
                countWrapper.eq(UserTagRelation::getTagId, tag.getId());
                countWrapper.eq(UserTagRelation::getRelType, 2);
                Long followCount = userTagRelationMapper.selectCount(countWrapper);
                vo.setFollowCount(followCount != null ? followCount.intValue() : 0);
                vo.setIsFollowing(true);
                tagList.add(vo);
            }
        }

        return ResponseResult.okResult(tagList);
    }

    @Override
    public ResponseResult follow(Integer tagId) {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = currentUser.getId().longValue();

        if (tagId == null) {
            return ResponseResult.errorResult(503, "标签ID不能为空");
        }

        SysTag tag = sysTagMapper.selectById(tagId);
        if (tag == null) {
            return ResponseResult.errorResult(503, "标签不存在");
        }

        // 检查是否已关注（rel_type=2）
        LambdaQueryWrapper<UserTagRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserTagRelation::getUserId, userId);
        wrapper.eq(UserTagRelation::getTagId, tagId);
        wrapper.eq(UserTagRelation::getRelType, 2);
        Long count = userTagRelationMapper.selectCount(wrapper);
        if (count > 0) {
            return ResponseResult.okResult();
        }

        UserTagRelation relation = new UserTagRelation();
        relation.setUserId(userId);
        relation.setTagId(tagId);
        relation.setRelType(2);
        userTagRelationMapper.insert(relation);

        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult unfollow(Integer tagId) {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = currentUser.getId().longValue();

        if (tagId == null) {
            return ResponseResult.errorResult(503, "标签ID不能为空");
        }

        LambdaQueryWrapper<UserTagRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserTagRelation::getUserId, userId);
        wrapper.eq(UserTagRelation::getTagId, tagId);
        wrapper.eq(UserTagRelation::getRelType, 2);
        userTagRelationMapper.delete(wrapper);

        return ResponseResult.okResult();
    }

    /**
     * 标签详情页：按标签名查询标签详情（id、标签名、关注数、当前用户是否已关注）
     * 标签不存在时返回错误结果，由前端渲染 404/空态
     */
    @Override
    public ResponseResult tagDetail(String tagName) {
        if (tagName == null || tagName.trim().isEmpty()) {
            return ResponseResult.errorResult(503, "标签名称不能为空");
        }
        String safeTag = tagName.trim();

        // 通过标签名定位 sys_tags（JSON 形式的文章标签以名称关联）
        LambdaQueryWrapper<SysTag> tagWrapper = new LambdaQueryWrapper<>();
        tagWrapper.eq(SysTag::getTagName, safeTag).last("limit 1");
        SysTag tag = sysTagMapper.selectOne(tagWrapper);
        if (tag == null) {
            return ResponseResult.errorResult(503, "标签不存在");
        }

        ApUser currentUser = AppThreadLocalUtil.getUser();
        Long userId = currentUser != null ? currentUser.getId().longValue() : null;

        // 关注数：user_tag_relation rel_type=2（关注标签）
        LambdaQueryWrapper<UserTagRelation> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(UserTagRelation::getTagId, tag.getId());
        countWrapper.eq(UserTagRelation::getRelType, 2);
        Long followCount = userTagRelationMapper.selectCount(countWrapper);

        // 当前用户是否已关注
        boolean isFollowed = false;
        if (userId != null) {
            LambdaQueryWrapper<UserTagRelation> followWrapper = new LambdaQueryWrapper<>();
            followWrapper.eq(UserTagRelation::getUserId, userId);
            followWrapper.eq(UserTagRelation::getTagId, tag.getId());
            followWrapper.eq(UserTagRelation::getRelType, 2);
            isFollowed = userTagRelationMapper.selectCount(followWrapper) > 0;
        }

        // null-safe：字符串""、数值0/原值
        Map<String, Object> map = new HashMap<>();
        map.put("id", tag.getId() != null ? tag.getId() : 0);
        map.put("tagName", tag.getTagName() != null ? tag.getTagName() : "");
        map.put("categoryCode", tag.getCategoryCode() != null ? tag.getCategoryCode() : "");
        map.put("categoryName", tag.getCategoryName() != null ? tag.getCategoryName() : "");
        map.put("followerCount", followCount != null ? followCount.longValue() : 0L);
        map.put("isFollowed", isFollowed);
        return ResponseResult.okResult(map);
    }
}