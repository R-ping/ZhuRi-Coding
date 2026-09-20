package com.heima.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.dto.BlockDTO;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.user.pojos.SysTag;
import com.heima.model.user.pojos.UserBlockRelation;
import com.heima.model.user.vo.BlockVO;
import com.heima.user.mapper.ApUserMapper;
import com.heima.user.mapper.SysTagMapper;
import com.heima.user.mapper.UserBlockRelationMapper;
import com.heima.user.service.BlockService;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class BlockServiceImpl implements BlockService {

    @Autowired
    private UserBlockRelationMapper userBlockRelationMapper;

    @Autowired
    private ApUserMapper apUserMapper;

    @Autowired
    private SysTagMapper sysTagMapper;

    @Override
    public ResponseResult getBlocks(Integer type, Integer page, Integer size) {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = currentUser.getId().longValue();

        if (type == null || (type != 1 && type != 2)) {
            return ResponseResult.errorResult(503, "无效的屏蔽类型");
        }

        Page<UserBlockRelation> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<UserBlockRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBlockRelation::getUserId, userId);
        wrapper.eq(UserBlockRelation::getTargetType, type);
        wrapper.orderByDesc(UserBlockRelation::getCreateTime);

        IPage<UserBlockRelation> result = userBlockRelationMapper.selectPage(pageParam, wrapper);

        List<BlockVO> voList = new ArrayList<>();
        for (UserBlockRelation block : result.getRecords()) {
            BlockVO vo = new BlockVO();
            vo.setId(block.getId());
            vo.setTargetType(block.getTargetType());
            vo.setTargetId(block.getTargetId());
            vo.setCreateTime(block.getCreateTime());

            if (type == 1) {
                // 作者：查询 ap_user 获取昵称和头像
                ApUser author = apUserMapper.selectById(block.getTargetId().intValue());
                if (author != null) {
                    vo.setTargetName(author.getNickname());
                    vo.setTargetAvatar(author.getImage());
                }
            } else if (type == 2) {
                // 标签：查询 sys_tags 获取标签名
                SysTag tag = sysTagMapper.selectById(block.getTargetId().intValue());
                if (tag != null) {
                    vo.setTargetName(tag.getTagName());
                }
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
    public ResponseResult addBlock(BlockDTO dto) {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = currentUser.getId().longValue();

        if (dto.getType() == null || (dto.getType() != 1 && dto.getType() != 2)) {
            return ResponseResult.errorResult(503, "无效的屏蔽类型");
        }
        if (dto.getTargetId() == null) {
            return ResponseResult.errorResult(503, "目标ID不能为空");
        }

        // 检查是否已存在
        LambdaQueryWrapper<UserBlockRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBlockRelation::getUserId, userId);
        wrapper.eq(UserBlockRelation::getTargetType, dto.getType());
        wrapper.eq(UserBlockRelation::getTargetId, dto.getTargetId());
        Long count = userBlockRelationMapper.selectCount(wrapper);
        if (count > 0) {
            return ResponseResult.errorResult(503, "已屏蔽，无需重复操作");
        }

        UserBlockRelation relation = new UserBlockRelation();
        relation.setUserId(userId);
        relation.setTargetType(dto.getType());
        relation.setTargetId(dto.getTargetId());
        relation.setCreateTime(new Date());
        userBlockRelationMapper.insert(relation);

        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult removeBlock(Long id) {
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = currentUser.getId().longValue();

        if (id == null) {
            return ResponseResult.errorResult(503, "屏蔽记录ID不能为空");
        }

        UserBlockRelation relation = userBlockRelationMapper.selectById(id);
        if (relation == null) {
            return ResponseResult.errorResult(503, "屏蔽记录不存在");
        }
        if (!relation.getUserId().equals(userId)) {
            return ResponseResult.errorResult(503, "无权操作该屏蔽记录");
        }

        userBlockRelationMapper.deleteById(id);
        return ResponseResult.okResult();
    }
}