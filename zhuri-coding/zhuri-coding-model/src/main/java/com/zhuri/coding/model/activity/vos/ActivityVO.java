package com.heima.model.activity.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class ActivityVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private String description;
    private String coverImage;
    private String activityType;
    private String status;
    private String category;
    private Date startDate;
    private Date endDate;
    private Long topicId;
    private Integer totalParticipants;
    private Long totalReadCount;
    private Date createdTime;
}