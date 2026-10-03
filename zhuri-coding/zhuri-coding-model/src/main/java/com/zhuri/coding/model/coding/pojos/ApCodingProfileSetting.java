package com.zhuri.coding.model.coding.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 能力档案隐私开关（Coding 延展第二层）
 *
 * <p><b>默认无记录 = 全私有</b>：档案主体（is_public）默认关闭，访客看到"未公开"占位；
 * 打开总开关后，各分项开关才生效（逐项可关，如隐藏"持续度"避免暴露活跃时间）。
 * 分项默认值：领域/持续度/输出/测评为公开（打开总开关即用），"解决问题"恒预留关闭。</p>
 */
@Data
@TableName("ap_coding_profile_setting")
public class ApCodingProfileSetting implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 总开关：0私有（默认） 1公开 */
    @TableField("is_public")
    private Integer isPublic;

    /** 技术领域分布是否公开 */
    @TableField("public_domain")
    private Integer publicDomain;

    /** 持续度是否公开 */
    @TableField("public_streak")
    private Integer publicStreak;

    /** 输出能力是否公开 */
    @TableField("public_output")
    private Integer publicOutput;

    /** 解决问题是否公开（依赖付费问答，预留恒关） */
    @TableField("public_solve")
    private Integer publicSolve;

    /** 测评成绩是否公开 */
    @TableField("public_assessment")
    private Integer publicAssessment;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;

    /** 分项开关判真：未设置时默认公开（与建表默认值一致） */
    public boolean domainPublic() {
        return publicDomain == null || publicDomain == 1;
    }

    /** 分项开关判真：未设置时默认公开 */
    public boolean streakPublic() {
        return publicStreak == null || publicStreak == 1;
    }

    /** 分项开关判真：未设置时默认公开 */
    public boolean outputPublic() {
        return publicOutput == null || publicOutput == 1;
    }

    /** 分项开关判真：未设置时默认公开 */
    public boolean assessmentPublic() {
        return publicAssessment == null || publicAssessment == 1;
    }
}