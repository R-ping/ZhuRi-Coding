package com.heima.content.service.column;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.column.pojos.ApColumn;
import com.heima.model.common.dtos.ResponseResult;

public interface ColumnService extends IService<ApColumn> {

    ResponseResult list(Long authorId, Integer page, Integer size, String status, String title);

    ResponseResult statistics(Long authorId);

    ResponseResult createColumn(ApColumn column);

    ResponseResult updateColumn(ApColumn column);

    ResponseResult deleteColumn(Long id);
}