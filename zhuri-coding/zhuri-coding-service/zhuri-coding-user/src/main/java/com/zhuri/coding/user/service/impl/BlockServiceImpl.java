package com.zhuri.coding.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.dto.BlockDTO;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.model.user.pojos.SysTag;
import com.zhuri.coding.model.user.pojos.UserBlockRelation;
import com.zhuri.coding.model.user.vo.BlockVO;
import com.zhuri.coding.user.mapper.ApUserMapper;
import com.zhuri.coding.user.mapper.SysTagMapper;
import com.zhuri.coding.user.mapper.UserBlockRelationMapper;
import com.zhuri.coding.user.service.BlockService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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

        // 分页参数限幅：size 不设上限时一次请求就能把整张关系表拉出来
        int safePage = page == null || page < 1 ? 1 : Math.min(page, 100);
        int safeSize = size == null || size <= 0 ? 10 : Math.min(size, 50);
        Page<UserBlockRelation> pageParam = new Page<>(safePage, safeSize);
        LambdaQueryWrapper<UserBlockRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBlockRelation::getUserId, userId);
        wrapper.eq(UserBlockRelation::getTargetType, type);
        wrapper.orderByDesc(UserBlockRelation::getCreateTime);

        IPage<UserBlockRelation> result = userBlockRelationMapper.selectPage(pageParam, wrapper);
        List<UserBlockRelation> records = result.getRecords();

        // 名称/头像一次性批量取回，避免"每条记录一次 selectById"的 N+1
        Map<Integer, ApUser> userMap = Collections.emptyMap();
        Map<Integer, SysTag> tagMap = Collections.emptyMap();
        if (!records.isEmpty()) {
            Set<Integer> targetIds = records.stream()
                .map(b -> b.getTargetId().intValue())
                .collect(Collectors.toSet());
            if (type == 1) {
                userMap = apUserMapper.selectBatchIds(targetIds).stream()
                    .collect(Collectors.toMap(ApUser::getId, u -> u));
            } else if (type == 2) {
                tagMap = sysTagMapper.selectBatchIds(targetIds).stream()
                    .collect(Collectors.toMap(SysTag::getId, t -> t));
            }
        }

        List<BlockVO> voList = new ArrayList<>();
        for (UserBlockRelation block : records) {
            BlockVO vo = new BlockVO();
            vo.setId(block.getId());
            vo.setTargetType(block.getTargetType());
            vo.setTargetId(block.getTargetId());
            vo.setCreateTime(block.getCreateTime());

            if (type == 1) {
                ApUser author = userMap.get(block.getTargetId().intValue());
                if (author != null) {
                    vo.setTargetName(author.getNickname());
                    vo.setTargetAvatar(author.getImage());
                }
            } else if (type == 2) {
                SysTag tag = tagMap.get(block.getTargetId().intValue());
                if (tag != null) {
                    vo.setTargetName(tag.getTagName());
                }
            }
            voList.add(vo);
        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("total", result.getTotal());
        resultMap.put("page", safePage);
        resultMap.put("size", safeSize);
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

    @Override
    public boolean isBlockedBothWays(Long userId, Long targetId) {
        if (userId == null || targetId == null) {
            return false;
        }
        // target_type = 1 表示被屏蔽对象是用户；两个方向任一命中即算互相不可见
        LambdaQueryWrapper<UserBlockRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBlockRelation::getTargetType, 1)
            .and(q -> q
                .nested(n -> n.eq(UserBlockRelation::getUserId, userId)
                    .eq(UserBlockRelation::getTargetId, targetId))
                .or()
                .nested(n -> n.eq(UserBlockRelation::getUserId, targetId)
                    .eq(UserBlockRelation::getTargetId, userId)));
        Long count = userBlockRelationMapper.selectCount(wrapper);
        return count != null && count > 0;
    }
}