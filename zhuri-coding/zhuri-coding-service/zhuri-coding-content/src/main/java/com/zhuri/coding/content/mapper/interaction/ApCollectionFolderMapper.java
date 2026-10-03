package com.zhuri.coding.content.mapper.interaction;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.behavior.pojos.ApCollectionFolder;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApCollectionFolderMapper extends BaseMapper<ApCollectionFolder> {

    /**
     * 查询某用户当前最大排序值（新建收藏夹取最大值+1，保证新夹排在最后）。
     *
     * @param userId 用户ID
     * @return 最大排序值；无收藏夹时返回 -1（新夹排序值为 0）
     */
    @Select("SELECT COALESCE(MAX(sort_order), -1) FROM ap_collection_folder WHERE user_id = #{userId}")
    Integer selectMaxSortOrder(@Param("userId") Integer userId);

    /**
     * 一次分组统计各收藏夹的条目数与最近使用时间，避免逐夹 count 的 N+1 查询。
     *
     * <p>最新使用时间由收藏记录派生（该夹内最新一条收藏的创建时间），不冗余存储：
     * 收藏选择面板"最近使用三个置顶"依赖它，派生方式保证与收藏数据永远一致。</p>
     *
     * @param userId 用户ID
     * @return 每行含 folderId / articleCount / lastUsedTime 三个键；无收藏的收藏夹不在结果中
     */
    @Select("SELECT folder_id AS folderId, COUNT(*) AS articleCount, MAX(created_time) AS lastUsedTime "
        + "FROM ap_collection WHERE user_id = #{userId} AND folder_id IS NOT NULL GROUP BY folder_id")
    List<Map<String, Object>> selectFolderStats(@Param("userId") Integer userId);
}