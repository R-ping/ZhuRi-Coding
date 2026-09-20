
package com.heima.content.controller.v1.channel;

import com.heima.content.service.channel.ChannelService;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/channel")
public class ChannelController {

    @Autowired
    private ChannelService channelService;

    @GetMapping("/channels")
    public ResponseResult findAll() {
        return channelService.findAll();
    }
}
