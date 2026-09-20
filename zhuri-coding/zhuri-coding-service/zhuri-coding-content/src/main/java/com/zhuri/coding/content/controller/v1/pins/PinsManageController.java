package com.heima.content.controller.v1.pins;

import com.heima.content.service.pins.PinsService;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/pins/manage")
public class PinsManageController {

    @Autowired
    private PinsService pinsService;

    @GetMapping("/list")
    public ResponseResult list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String status) {
        return pinsService.list(null, page, size, status);
    }

    @GetMapping("/statistics")
    public ResponseResult statistics() {
        return pinsService.statistics(null);
    }

    @PostMapping("/create")
    public ResponseResult create(@RequestBody ApPins pins) {
        return pinsService.createPins(pins);
    }

    @PostMapping("/delete")
    public ResponseResult delete(@RequestBody Map<String, Long> body) {
        return pinsService.deletePins(body.get("id"));
    }
}