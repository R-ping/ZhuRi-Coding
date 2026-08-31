package com.heima.model.schedule.pojos;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 
 * </p>
 *
 * @author itheima
 */
@Data
@TableName("taskinfo_logs")
public class TaskinfoLogs implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long taskId;
    /**
     * 执行时间
     */
    @TableField("execute_time")
    private Date executeTime;
    @TableField("in_one_hour")
    private boolean inOneHour;
    /**
     * 参数
     */
    @TableField("parameters")
    private byte[] parameters;
    /**
     * 版本号,用乐观锁
     */
    @Version
    private Integer version;
    /**
     * 状态 0=int 1=EXECUTING 2=COMPLETED 3=CANCELLED
     */
    @TableField("status")
    private Integer status;
    /**
     * 预执行时间
     * lastExecInterval-5~10分钟，提前执行复杂业务
     */
    private long firstExecInterval;
    /**
     * 执行时间
     */
    private long lastExecInterval;

}
