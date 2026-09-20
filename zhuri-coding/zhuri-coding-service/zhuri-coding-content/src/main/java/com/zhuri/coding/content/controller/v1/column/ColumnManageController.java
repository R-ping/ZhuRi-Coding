package com.heima.content.controller.v1.column;

import com.heima.content.service.column.ColumnService;
import com.heima.model.column.pojos.ApColumn;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/column/manage")
public class ColumnManageController {

    @Autowired
    private ColumnService columnService;

    @GetMapping("/list")
    public ResponseResult list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String title) {
        return columnService.list(null, page, size, status, title);
    }

    @GetMapping("/statistics")
    public ResponseResult statistics() {
        return columnService.statistics(null);
    }

    @PostMapping("/create")
    public ResponseResult create(@RequestBody ApColumn column) {
        return columnService.createColumn(column);
    }

    @PostMapping("/update")
    public ResponseResult update(@RequestBody ApColumn column) {
        return columnService.updateColumn(column);
    }

    @PostMapping("/delete")
    public ResponseResult delete(@RequestBody Map<String, Long> body) {
        return columnService.deleteColumn(body.get("id"));
    }
}