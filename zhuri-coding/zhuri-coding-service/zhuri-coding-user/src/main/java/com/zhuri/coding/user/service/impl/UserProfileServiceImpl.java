package com.zhuri.coding.user.service.impl;

import com.aliyun.oss.OSS;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.article.IUserStatsClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.dto.ProfileUpdateDTO;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.model.user.pojos.SysTag;
import com.zhuri.coding.model.user.pojos.UserProfile;
import com.zhuri.coding.model.user.pojos.UserTagRelation;
import com.zhuri.coding.model.user.vo.TagGroupVO;
import com.zhuri.coding.model.user.vo.TagVO;
import com.zhuri.coding.model.user.vo.UserProfileVO;
import com.zhuri.coding.model.user.vo.UserStatsVO;
import com.zhuri.coding.user.config.OssConfig;
import com.zhuri.coding.user.mapper.SysTagMapper;
import com.zhuri.coding.user.mapper.UserProfileMapper;
import com.zhuri.coding.user.mapper.UserTagRelationMapper;
import com.zhuri.coding.user.service.UserProfileService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserProfileServiceImpl implements UserProfileService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Autowired
    private SysTagMapper sysTagMapper;

    @Autowired
    private UserTagRelationMapper userTagRelationMapper;

    @Autowired
    private OssConfig ossConfig;

    @Autowired
    private OSS ossClient;

    @Autowired
    private IUserStatsClient userStatsClient;

    @Override
    public ResponseResult getProfile() {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = currentUser.getId().longValue();

        // 查询用户资料
        UserProfile profile = userProfileMapper.selectById(userId);
        UserProfileVO vo = new UserProfileVO();
        vo.setUserId(userId);

        if (profile != null) {
            vo.setUsername(profile.getUsername());
            vo.setAvatarUrl(profile.getAvatarUrl());
            vo.setCareerStartDate(profile.getCareerStartDate());
            vo.setCareerDirection(profile.getCareerDirection());
            vo.setPosition(profile.getPosition());
            vo.setCompany(profile.getCompany());
            vo.setWebsite(profile.getWebsite());
            vo.setBio(profile.getBio());
            vo.setRegion(profile.getRegion());
            vo.setEducation(profile.getEducation());
            vo.setSkills(parseSkills(profile.getSkills()));
            vo.setLevel(profile.getLevel());
        } else {
            // 使用默认用户名
            vo.setUsername(currentUser.getNickname() != null ? currentUser.getNickname() : "");
            vo.setAvatarUrl("");
            vo.setCareerDirection("");
            vo.setPosition("");
            vo.setCompany("");
            vo.setWebsite("");
            vo.setBio("");
        }

        // 查询已选标签
        LambdaQueryWrapper<UserTagRelation> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.eq(UserTagRelation::getUserId, userId);
        List<UserTagRelation> relations = userTagRelationMapper.selectList(relationWrapper);
        List<Integer> selectedTagIds = relations.stream()
                .map(UserTagRelation::getTagId)
                .collect(Collectors.toList());
        vo.setSelectedTagIds(selectedTagIds);

        // 查询全量标签并分组（标签表为小表，加排序和上限保证安全）
        QueryWrapper<SysTag> tagQuery = new QueryWrapper<>();
        tagQuery.orderByAsc("category_code", "id");
        tagQuery.last("LIMIT 500");
        List<SysTag> allTags = sysTagMapper.selectList(tagQuery);
        Map<String, List<TagVO>> tagGroupMap = new LinkedHashMap<>();
        Map<String, String> categoryNameMap = new LinkedHashMap<>();
        for (SysTag tag : allTags) {
            String code = tag.getCategoryCode();
            categoryNameMap.putIfAbsent(code, tag.getCategoryName());
            tagGroupMap.computeIfAbsent(code, k -> new ArrayList<>())
                    .add(toTagVO(tag));
        }

        List<TagGroupVO> tagGroups = new ArrayList<>();
        for (Map.Entry<String, List<TagVO>> entry : tagGroupMap.entrySet()) {
            TagGroupVO group = new TagGroupVO();
            group.setCategoryCode(entry.getKey());
            group.setCategoryName(categoryNameMap.get(entry.getKey()));
            group.setTags(entry.getValue());
            tagGroups.add(group);
        }
        vo.setTagGroups(tagGroups);

        // 掘金式对象统计（跨服务查询聚合，内容服务不可用时降级为空统计）
        UserStatsVO stats = userStatsClient.stats(userId);
        if (stats != null) {
            vo.setArticleCount(stats.getArticleCount());
            vo.setPinCount(stats.getPinCount());
            vo.setDiggCount(stats.getDiggCount());
            vo.setViewCount(stats.getViewCount());
            vo.setFollowerCount(stats.getFollowerCount());
            vo.setFollowCount(stats.getFollowCount());
        }

        return ResponseResult.okResult(vo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateProfile(ProfileUpdateDTO dto) {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = currentUser.getId().longValue();

        // 校验用户名
        if (dto.getUsername() == null || dto.getUsername().trim().isEmpty()) {
            return ResponseResult.errorResult(503, "用户名不能为空");
        }
        String username = dto.getUsername().trim();
        if (username.length() < 5 || username.length() > 20) {
            return ResponseResult.errorResult(503, "用户名长度应为5-20个字符");
        }

        // 校验职业方向
        if (dto.getCareerDirection() == null || dto.getCareerDirection().trim().isEmpty()) {
            return ResponseResult.errorResult(503, "职业方向不能为空");
        }

        // 校验标签
        if (dto.getTagIds() == null || dto.getTagIds().isEmpty()) {
            return ResponseResult.errorResult(503, "请至少选择一个兴趣标签");
        }

        // 查询或创建 user_profile
        UserProfile profile = userProfileMapper.selectById(userId);
        if (profile == null) {
            profile = new UserProfile();
            profile.setUserId(userId);
        }

        profile.setUsername(username);
        profile.setCareerStartDate(dto.getCareerStartDate());
        profile.setCareerDirection(dto.getCareerDirection().trim());
        profile.setPosition(dto.getPosition() != null ? dto.getPosition().trim() : null);
        profile.setCompany(dto.getCompany() != null ? dto.getCompany().trim() : null);
        profile.setWebsite(dto.getWebsite() != null ? dto.getWebsite().trim() : null);
        profile.setBio(dto.getBio() != null ? dto.getBio().trim() : null);
        profile.setRegion(dto.getRegion() != null ? dto.getRegion().trim() : null);
        profile.setEducation(dto.getEducation() != null ? dto.getEducation().trim() : null);
        profile.setSkills(serializeSkills(dto.getSkills()));
        profile.setUpdateTime(new Date());

        if (userProfileMapper.selectById(userId) != null) {
            userProfileMapper.updateById(profile);
        } else {
            userProfileMapper.insert(profile);
        }

        // 原子替换标签关联（先删后插）
        LambdaQueryWrapper<UserTagRelation> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(UserTagRelation::getUserId, userId);
        userTagRelationMapper.delete(deleteWrapper);

        for (Integer tagId : dto.getTagIds()) {
            UserTagRelation relation = new UserTagRelation();
            relation.setUserId(userId);
            relation.setTagId(tagId);
            userTagRelationMapper.insert(relation);
        }

        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult uploadAvatar(MultipartFile file) {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = currentUser.getId().longValue();

        if (file == null || file.isEmpty()) {
            return ResponseResult.errorResult(503, "请选择要上传的图片");
        }

        // 校验文件类型
        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/jpeg")
                && !contentType.equals("image/png")
                && !contentType.equals("image/webp"))) {
            return ResponseResult.errorResult(503, "仅支持JPG、PNG、WebP格式的图片");
        }

        // 校验文件大小（5MB）
        if (file.getSize() > 5 * 1024 * 1024) {
            return ResponseResult.errorResult(503, "图片大小不能超过5MB");
        }

        try {
            // 生成文件名
            String originalFilename = file.getOriginalFilename();
            String suffix = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".jpg";
            String objectKey = ossConfig.getDir() + "avatar/" + userId + "_" + System.currentTimeMillis() + suffix;

            // 上传到OSS
            ossClient.putObject(ossConfig.getBucket(), objectKey, file.getInputStream());

            // 构建CDN URL
            String avatarUrl = ossConfig.getHost() + "/" + objectKey;

            // 更新 user_profile
            UserProfile profile = userProfileMapper.selectById(userId);
            if (profile == null) {
                profile = new UserProfile();
                profile.setUserId(userId);
            }
            profile.setAvatarUrl(avatarUrl);
            profile.setUpdateTime(new Date());
            if (profile.getUserId() != null && userProfileMapper.selectById(userId) != null) {
                userProfileMapper.updateById(profile);
            } else {
                userProfileMapper.insert(profile);
            }

            Map<String, String> result = new HashMap<>();
            result.put("url", avatarUrl);
            return ResponseResult.okResult(result);
        } catch (IOException e) {
            log.error("头像上传失败: {}", e.getMessage());
            return ResponseResult.errorResult(503, "头像上传失败");
        }
    }

    private TagVO toTagVO(SysTag tag) {
        TagVO vo = new TagVO();
        vo.setId(tag.getId());
        vo.setTagName(tag.getTagName());
        return vo;
    }

    /** 技能标签存储为 JSON 数组字符串，读取时安全解析 */
    private List<String> parseSkills(String skillsJson) {
        if (skillsJson == null || skillsJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(skillsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析 skills 失败，原样返回空列表: {}", skillsJson);
            return new ArrayList<>();
        }
    }

    /** 技能标签序列化为 JSON 数组字符串（null 存为 null） */
    private String serializeSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(skills);
        } catch (Exception e) {
            log.warn("序列化 skills 失败: {}", e.getMessage());
            return null;
        }
    }
}