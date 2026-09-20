package com.heima.content.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OssImageCleanupMapper {

    List<String> findArticleCoverImages(@Param("startTime") String startTime, @Param("endTime") String endTime);

    List<String> findArticleContentImages(@Param("startTime") String startTime, @Param("endTime") String endTime);

    List<String> findPinsImages(@Param("startTime") String startTime, @Param("endTime") String endTime);

    List<String> findColumnCoverImages(@Param("startTime") String startTime, @Param("endTime") String endTime);

    List<String> findCourseCoverImages(@Param("startTime") String startTime, @Param("endTime") String endTime);
}