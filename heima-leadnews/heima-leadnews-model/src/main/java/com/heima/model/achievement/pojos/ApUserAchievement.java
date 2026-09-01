package com.heima.model.achievement.pojos;

import java.util.Date;
import lombok.Data;

/**
 * 用户成就解锁记录（事件驱动写入）
 * 由行为事件处理器在行为发生时检查并落库，成就页查询只读本表
 */
@Data
public class ApUserAchievement {

    private Long id;

    /** 用户ID */
    private Long userId;

    /** 勋章编码（关联 ap_achievement.code） */
    private String achievementCode;

    /** 当前进度快照（只增不减） */
    private Long progress;

    /** 解锁阈值快照 */
    private Integer threshold;

    /** 是否解锁：1=已解锁 */
    private Boolean unlocked;

    /** 解锁时间 */
    private Date unlockedAt;

    /** 更新时间 */
    private Date updatedAt;
}
