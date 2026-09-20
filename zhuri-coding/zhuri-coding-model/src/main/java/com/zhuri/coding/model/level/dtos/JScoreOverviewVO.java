package com.heima.model.level.dtos;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class JScoreOverviewVO {
    private String statDate;
    private Map<String, TodayTotalVO> summary;
    private ChartData chart;

    @Data
    public static class TodayTotalVO {
        private BigDecimal today;
        private BigDecimal total;
    }

    @Data
    public static class ChartData {
        private List<String> dimensions;
        private List<BigDecimal> values;
    }
}