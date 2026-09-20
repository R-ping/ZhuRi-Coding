package com.zhuri.coding.reward.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 签到奖励计算工具类
 * 基于连续天数 d 计算奖励，30天一个周期
 */
public class SignRewardUtil {

    private static final int CYCLE = 30;

    private static final int[] REWARD_TABLE = {
        100, 150, 512, 250, 300, 350, 1024, 450, 500, 550,
        600, 650, 700, 2048, 700, 700, 700, 700, 700, 700,
        4096, 700, 700, 700, 700, 700, 700, 700, 700, 5120
    };

    /**
     * 根据连续天数获取奖励矿石数
     * @param continuousDays 连续天数（从1开始）
     * @return 奖励矿石数
     */
    public static int getRewardByContinuousDays(int continuousDays) {
        if (continuousDays <= 0) return 0;
        int index = (continuousDays - 1) % CYCLE;
        return REWARD_TABLE[index];
    }

    /**
     * 判断指定连续天数是否为特殊奖励日（高额奖励）
     */
    public static boolean isSpecialDay(int continuousDays) {
        if (continuousDays <= 0) return false;
        int index = (continuousDays - 1) % CYCLE;
        int reward = REWARD_TABLE[index];
        return reward > 700;
    }

    /**
     * 构建里程碑进度数据（30 天周期内每天的目标与达成状态）。
     * 纯函数：签到状态接口与签到/补签结果共用，自 CheckinServiceImpl 迁出以便事务拆分后复用。
     */
    public static Map<String, Object> milestoneProgress(int continuousDays) {
        int[] specialDays = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30};
        int[] specialOres = {100, 150, 512, 250, 300, 350, 1024, 450, 500, 550, 600, 650, 700, 2048, 700, 700, 700, 700, 700, 700, 4096, 700, 700, 700, 700, 700, 700, 700, 700, 5120};

        List<Map<String, Object>> specialDayList = new ArrayList<>();
        for (int i = 0; i < specialDays.length; i++) {
            int sd = specialDays[i];
            int ore = specialOres[i];
            Map<String, Object> m = new HashMap<>();
            m.put("day", sd);
            m.put("ore", ore);
            m.put("achieved", continuousDays >= sd);
            m.put("isCurrent", continuousDays == sd);
            m.put("isSpecial", ore > 700);
            specialDayList.add(m);
        }

        int percent = Math.min((int) (((continuousDays % 30) * 100.0) / 30), 100);

        Map<String, Object> progress = new HashMap<>();
        progress.put("current", continuousDays % 30 == 0 ? 30 : continuousDays % 30);
        progress.put("total", 30);
        progress.put("percent", percent);
        progress.put("specialDays", specialDayList);

        return progress;
    }

    /**
     * 构建下一个特殊奖励节点信息（3/7/14/21/30 天节点；当前周期走完则给下一周期的第 3 天）。
     * 纯函数：与 {@link #milestoneProgress(int)} 同源迁出。
     */
    public static Map<String, Object> nextSpecial(int currentContinuousDays) {
        int[] specialDays = {3, 7, 14, 21, 30};
        int[] specialOres = {512, 1024, 2048, 4096, 5120};
        int periodDay = currentContinuousDays % 30 == 0 ? 30 : currentContinuousDays % 30;

        for (int i = 0; i < specialDays.length; i++) {
            if (periodDay < specialDays[i]) {
                int daysLeft = specialDays[i] - periodDay;
                Map<String, Object> result = new HashMap<>();
                result.put("day", specialDays[i]);
                result.put("ore", specialOres[i]);
                result.put("daysLeft", daysLeft);
                return result;
            }
        }
        // 当前周期已过所有特殊节点，返回下一个周期的第一个特殊节点
        int daysLeft = (30 - periodDay) + 3;
        Map<String, Object> result = new HashMap<>();
        result.put("day", 3);
        result.put("ore", 512);
        result.put("daysLeft", daysLeft);
        return result;
    }
}