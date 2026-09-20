package com.zhuri.coding.content.service.column;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhuri.coding.model.column.pojos.ApColumn;
import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface ColumnService extends IService<ApColumn> {

    ResponseResult list(Long authorId, Integer page, Integer size, String status, String title);

    ResponseResult statistics(Long authorId);

    ResponseResult createColumn(ApColumn column);

    ResponseResult updateColumn(ApColumn column);

    ResponseResult deleteColumn(Long id);
}