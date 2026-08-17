package com.heima.content.service.course.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.course.ApAuthorProfileMapper;
import com.heima.content.service.course.AuthorProfileService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.course.dtos.AuthorProfileDto;
import com.heima.model.course.pojos.ApAuthorProfile;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AuthorProfileServiceImpl implements AuthorProfileService {

    @Autowired
    private ApAuthorProfileMapper authorProfileMapper;

    @Override
    public ResponseResult getProfile(Integer userId) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        ApAuthorProfile profile = authorProfileMapper.selectOne(
                new LambdaQueryWrapper<ApAuthorProfile>()
                        .eq(ApAuthorProfile::getUserId, userId)
                        .last("LIMIT 1"));

        Map<String, Object> data = new HashMap<>();
        data.put("hasProfile", profile != null);
        // 返回字段均为空串而非 null，前端无需判空（未填则回填空）
        data.put("realName", profile != null ? nvl(profile.getRealName()) : "");
        data.put("position", profile != null ? nvl(profile.getPosition()) : "");
        data.put("resume", profile != null ? nvl(profile.getResume()) : "");
        data.put("applyReason", profile != null ? nvl(profile.getApplyReason()) : "");
        data.put("contactWechat", profile != null ? nvl(profile.getContactWechat()) : "");
        data.put("contactEmail", profile != null ? nvl(profile.getContactEmail()) : "");
        data.put("blogs", profile != null ? nvl(profile.getBlogs()) : "");
        data.put("personalIntro", profile != null ? nvl(profile.getPersonalIntro()) : "");
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult saveProfile(Integer userId, AuthorProfileDto dto) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请求参数不能为空");
        }

        ApAuthorProfile existing = authorProfileMapper.selectOne(
                new LambdaQueryWrapper<ApAuthorProfile>()
                        .eq(ApAuthorProfile::getUserId, userId)
                        .last("LIMIT 1"));

        Date now = new Date();
        if (existing == null) {
            ApAuthorProfile profile = new ApAuthorProfile();
            profile.setUserId(userId);
            applyDto(profile, dto);
            profile.setCreatedTime(now);
            profile.setUpdatedTime(now);
            authorProfileMapper.insert(profile);
        } else {
            applyDto(existing, dto);
            existing.setUpdatedTime(now);
            authorProfileMapper.updateById(existing);
        }
        return ResponseResult.okResult(Map.of("hasProfile", true));
    }

    /** 将 DTO 基础信息覆盖到实体（允许重新填写个人基础信息） */
    private void applyDto(ApAuthorProfile profile, AuthorProfileDto dto) {
        profile.setRealName(trim(dto.getRealName()));
        profile.setPosition(trim(dto.getPosition()));
        profile.setResume(trim(dto.getResume()));
        profile.setApplyReason(trim(dto.getApplyReason()));
        profile.setContactWechat(trim(dto.getContactWechat()));
        profile.setContactEmail(trim(dto.getContactEmail()));
        profile.setBlogs(trim(dto.getBlogs()));
        profile.setPersonalIntro(trim(dto.getPersonalIntro()));
    }

    private String trim(String s) {
        return s != null ? s.trim() : "";
    }

    private String nvl(String s) {
        return s != null ? s : "";
    }
}