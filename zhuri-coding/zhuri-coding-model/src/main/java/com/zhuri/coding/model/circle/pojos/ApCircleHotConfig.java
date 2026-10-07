package com.zhuri.coding.model.circle.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("ap_circle_hot_config")
public class ApCircleHotConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 人气位条数上限（{@code display_order} 的取值区间是 1..5，DDL 注释里就写着"展示顺序 1-5"）。
     *
     * <p>这个数字原来只存在于两处各自硬编码：建表注释和 {@code CircleServiceImpl#hot()} 的
     * {@code LIMIT 5}。运营位配置接口上线后会新增第三处（"最多配几个"），
     * 三处里任意一处单独改动都会得到"配了却不展示"或"展示位空着"，所以收在这里一份，
     * 读路径与写路径都引它。
     */
    public static final int MAX_DISPLAY_ORDER = 5;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @TableField("circle_id")
    private Long circleId;

    @TableField("display_order")
    private Integer displayOrder;
}