package com.zhuri.coding.content.service.course;

import com.zhuri.coding.model.course.dtos.ChapterDto;
import com.zhuri.coding.model.course.dtos.ChapterSortDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface ApCourseChapterService {

    /** 创建章节 */
    ResponseResult createChapter(ChapterDto dto, Long userId);

    /** 更新章节 */
    ResponseResult updateChapter(ChapterDto dto, Long userId);

    /** 删除章节 */
    ResponseResult deleteChapter(Long chapterId, Long userId);

    /** 批量更新章节排序 */
    ResponseResult updateSort(ChapterSortDto dto, Long userId);

    /** 公开获取章节详情（用于阅读） */
    ResponseResult getChapterDetail(Long chapterId);

    /** 作者提交小节审核（0草稿→2审核中），可附留言 */
    ResponseResult submitForReview(Long chapterId, String note, Long userId);
}