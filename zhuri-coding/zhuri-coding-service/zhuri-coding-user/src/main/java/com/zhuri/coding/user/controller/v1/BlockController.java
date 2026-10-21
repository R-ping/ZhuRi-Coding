package com.zhuri.coding.user.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.dto.BlockDTO;
import com.zhuri.coding.user.service.BlockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
@Slf4j
public class BlockController {

    @Autowired
    private BlockService blockService;

    @GetMapping("/blocks")
    public ResponseResult getBlocks(@RequestParam(defaultValue = "1") Integer type,
                                     @RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "10") Integer size) {
        return blockService.getBlocks(type, page, size);
    }

    @PostMapping("/blocks")
    public ResponseResult addBlock(@RequestBody BlockDTO dto) {
        return blockService.addBlock(dto);
    }

    @DeleteMapping("/blocks/{id}")
    public ResponseResult removeBlock(@PathVariable Long id) {
        return blockService.removeBlock(id);
    }

    /**
     * 内部接口：双向拉黑判定。供私信服务在投递前确认双方是否互相不可见。
     */
    @GetMapping("/feign/blocks/check")
    public ResponseResult checkBlocked(@RequestParam("userId") Long userId,
                                       @RequestParam("targetId") Long targetId) {
        Map<String, Object> data = new HashMap<>();
        data.put("blocked", blockService.isBlockedBothWays(userId, targetId));
        return ResponseResult.okResult(data);
    }
}