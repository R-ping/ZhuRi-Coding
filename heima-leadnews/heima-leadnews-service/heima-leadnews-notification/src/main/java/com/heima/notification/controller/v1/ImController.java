package com.heima.notification.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.notification.dtos.ImMessageDto;
import com.heima.model.notification.dtos.ImReadDto;
import com.heima.model.user.pojos.ApUser;
import com.heima.notification.service.ImService;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/im")
public class ImController {

    @Autowired
    private ImService imService;

    @GetMapping("/sessions")
    public ResponseResult listSessions() {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : null;
        return imService.listSessions(userId);
    }

    @GetMapping("/session")
    public ResponseResult getOrCreateSession(@RequestParam("peer_id") Long peerId) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : null;
        return imService.getOrCreateSession(userId, peerId);
    }

    @GetMapping("/messages")
    public ResponseResult listMessages(
            @RequestParam("session_id") Long sessionId,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : null;
        return imService.listMessages(userId, sessionId, cursor, size);
    }

    @PostMapping("/messages")
    public ResponseResult sendMessage(@RequestBody ImMessageDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : null;
        return imService.sendMessage(userId, dto);
    }

    @PostMapping("/messages/read")
    public ResponseResult markRead(@RequestBody ImReadDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : null;
        return imService.markRead(userId, dto);
    }
}