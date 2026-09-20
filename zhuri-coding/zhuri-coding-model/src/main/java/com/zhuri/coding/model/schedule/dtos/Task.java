package com.heima.model.schedule.dtos;

import java.util.Date;
import lombok.Data;

import java.io.Serializable;

@Data
public class Task implements Serializable {

    /**
     * 任务id
     */
    private Long taskId;
    /**
     * 预执行时间
     * lastExecInterval-5~10分钟，提前执行复杂业务
     */
    private long firstExecInterval;
    /**
     * 具体的执行时间间隔
     */
    private long objExecInterval;

    private Date executeTime;
    /**
     * task参数
     */
    private byte[] parameters;
    
}