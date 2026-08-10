<template>
  <div class="level-container">
    <div class="level-card daily-level">
      <div class="level-header">
        <div class="level-icon">☀️</div>
        <div class="level-title">逐日等级</div>
      </div>
      <div class="level-main">
        <div class="level-value">Lv.{{ levelInfo.dailyLevel }}</div>
        <div class="level-score">{{ levelInfo.dailyScore }} 逐日分</div>
      </div>
      <div class="level-progress">
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: dailyProgress + '%' }"></div>
        </div>
        <div class="progress-text" v-if="levelInfo.dailyLevel < 10">距离下一级还需 {{ dailyNextLevelScore }} 分</div>
        <div class="progress-text" v-else>已满级</div>
      </div>
      <div class="level-title-name">{{ levelInfo.dailyTitle }}</div>
    </div>

    <div class="level-card power-level">
      <div class="level-header">
        <div class="level-icon">💪</div>
        <div class="level-title">逐力值</div>
      </div>
      <div class="level-main">
        <div class="level-value">Lv.{{ levelInfo.powerLevel }}</div>
        <div class="level-score">{{ levelInfo.powerValue }} 逐力值</div>
      </div>
      <div class="level-progress">
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: powerProgress + '%' }"></div>
        </div>
        <div class="progress-text">距离下一级还需 {{ powerNextLevelScore }} 分</div>
      </div>
      <div class="level-title-name">{{ levelInfo.powerTitle }}</div>
    </div>

    <div class="permissions-section" v-if="permissions.length > 0">
      <div class="section-title">已解锁权限</div>
      <div class="permissions-grid">
        <div v-for="perm in permissions" :key="perm.code" class="permission-item">
          <span class="permission-icon">{{ perm.icon }}</span>
          <span class="permission-name">{{ perm.name }}</span>
        </div>
      </div>
    </div>

    <div class="level-rules">
      <div class="rules-title">等级规则</div>
      <div class="rules-content">
        <div class="rule-section">
          <div class="rule-title">☀️ 逐日等级获取方式</div>
          <ul class="rule-list">
            <li>每日登录 +10分</li>
            <li>阅读文章 +2分</li>
            <li>发表评论 +5分</li>
            <li>点赞 +1分</li>
            <li>分享 +3分</li>
            <li>关注 +2分</li>
          </ul>
        </div>
        <div class="rule-section">
          <div class="rule-title">💪 逐力值获取方式</div>
          <ul class="rule-list">
            <li>发布文章 +10分</li>
            <li>文章被点赞 +2分</li>
            <li>文章被评论 +3分</li>
            <li>文章被收藏 +5分</li>
            <li>文章被阅读 +1分</li>
          </ul>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { toast } from "@/utils/toast"

export default {
  name: 'LevelPanel',
  props: {
    userId: {
      type: Number,
      required: true
    }
  },
  data() {
    return {
      levelInfo: {
        dailyScore: 0,
        dailyLevel: 1,
        dailyTitle: '',
        dailyDescription: '',
        powerValue: 0,
        powerLevel: 1,
        powerTitle: '',
        powerDescription: ''
      },
      permissions: [],
      dailyNextLevelScore: 0,
      powerNextLevelScore: 0
    }
  },
  computed: {
    dailyProgress() {
      const levelConfig = this.getDailyLevelConfig()
      if (!levelConfig) return 0
      const min = levelConfig.minScore
      const max = levelConfig.maxScore
      const current = this.levelInfo.dailyScore
      this.dailyNextLevelScore = max - current + 1
      return ((current - min) / (max - min)) * 100
    },
    powerProgress() {
      const levelConfig = this.getPowerLevelConfig()
      if (!levelConfig) return 0
      const min = levelConfig.minScore
      const max = levelConfig.maxScore
      const current = this.levelInfo.powerValue
      this.powerNextLevelScore = levelConfig.level === 10 ? 0 : max - current + 1
      return levelConfig.level === 10 ? 100 : ((current - min) / (max - min)) * 100
    }
  },
  mounted() {
    this.loadLevelInfo()
  },
  methods: {
    async loadLevelInfo() {
      try {
        const response = await this.$http.get(`/api/v1/level/user/${this.userId}/info`)
        if (response.data) {
          this.levelInfo = response.data
        }
      } catch (error) {
        this.loadMockLevelInfo()
      }

      try {
        const response = await this.$http.get(`/api/v1/level/user/${this.userId}/permissions`)
        if (response.data) {
          this.permissions = this.mapPermissions(response.data)
        }
      } catch (error) {
        this.permissions = this.getMockPermissions()
      }
    },
    loadMockLevelInfo() {
      this.levelInfo = {
        dailyScore: 1250,
        dailyLevel: 3,
        dailyTitle: '中级掘友',
        dailyDescription: '社区的中坚力量',
        powerValue: 2300,
        powerLevel: 4,
        powerTitle: '高级创作者',
        powerDescription: '创作经验丰富'
      }
    },
    getMockPermissions() {
      return [
        { code: 'can_send_private_message', name: '私信权限', icon: '💬' },
        { code: 'can_set_comment_permission', name: '评论区权限设置', icon: '🔒' },
        { code: 'can_add_video', name: '添加视频', icon: '🎬' },
        { code: 'can_add_2_tags', name: '2个标签', icon: '🏷️' },
        { code: 'can_schedule_publish', name: '定时发布', icon: '⏰' },
        { code: 'can_add_3_tags', name: '3个标签', icon: '🏷️🏷️' }
      ]
    },
    mapPermissions(permissionCodes) {
      const permissionMap = {
        'can_send_private_message': { name: '私信权限', icon: '💬' },
        'can_set_comment_permission': { name: '评论区权限设置', icon: '🔒' },
        'can_create_poll': { name: '发起投票', icon: '📊' },
        'can_become_contributor': { name: '成为共建者', icon: '⭐' },
        'can_be_recommended': { name: '文章自动推荐', icon: '🔥' },
        'can_add_video': { name: '添加视频', icon: '🎬' },
        'can_add_2_tags': { name: '2个标签', icon: '🏷️' },
        'can_schedule_publish': { name: '定时发布', icon: '⏰' },
        'can_add_3_tags': { name: '3个标签', icon: '🏷️🏷️' },
        'can_add_4_tags': { name: '4个标签', icon: '🏷️🏷️🏷️' },
        'can_create_course': { name: '创作小册', icon: '📚' }
      }
      return permissionCodes.map(code => ({
        code,
        ...permissionMap[code] || { name: code, icon: '✨' }
      }))
    },
    getDailyLevelConfig() {
      const level = this.levelInfo.dailyLevel
      const configs = [
        { level: 1, minScore: 0, maxScore: 99 },
        { level: 2, minScore: 100, maxScore: 499 },
        { level: 3, minScore: 500, maxScore: 1499 },
        { level: 4, minScore: 1500, maxScore: 2999 },
        { level: 5, minScore: 3000, maxScore: 4999 },
        { level: 6, minScore: 5000, maxScore: 7999 },
        { level: 7, minScore: 8000, maxScore: 11999 },
        { level: 8, minScore: 12000, maxScore: 19999 },
        { level: 9, minScore: 20000, maxScore: 29999 },
        { level: 10, minScore: 30000, maxScore: 999999 }
      ]
      return configs.find(c => c.level === level)
    },
    getPowerLevelConfig() {
      const level = this.levelInfo.powerLevel
      const configs = [
        { level: 1, minScore: 0, maxScore: 99 },
        { level: 2, minScore: 100, maxScore: 499 },
        { level: 3, minScore: 500, maxScore: 1499 },
        { level: 4, minScore: 1500, maxScore: 2999 },
        { level: 5, minScore: 3000, maxScore: 4999 },
        { level: 6, minScore: 5000, maxScore: 7999 },
        { level: 7, minScore: 8000, maxScore: 11999 },
        { level: 8, minScore: 12000, maxScore: 19999 },
        { level: 9, minScore: 20000, maxScore: 29999 },
        { level: 10, minScore: 30000, maxScore: 999999 }
      ]
      return configs.find(c => c.level === level)
    }
  }
}
</script>

<style lang="less" scoped>
.level-container {
  max-width: 800px;
  margin: 0 auto;
  padding: 20px;
}

.level-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.level-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.level-icon {
  font-size: 20px;
}

.level-title {
  font-size: 14px;
  color: #666;
}

.level-main {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
}

.level-value {
  font-size: 28px;
  font-weight: 700;
  color: #1a1a1a;
}

.daily-level .level-value {
  color: #FF6B35;
}

.power-level .level-value {
  color: #6C5CE7;
}

.level-score {
  font-size: 14px;
  color: #999;
}

.level-progress {
  margin-bottom: 8px;
}

.progress-bar {
  height: 6px;
  background: #f0f0f0;
  border-radius: 3px;
  overflow: hidden;
  margin-bottom: 6px;
}

.progress-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.3s ease;
}

.daily-level .progress-fill {
  background: linear-gradient(90deg, #FF6B35, #FF8E53);
}

.power-level .progress-fill {
  background: linear-gradient(90deg, #6C5CE7, #A29BFE);
}

.progress-text {
  font-size: 12px;
  color: #999;
}

.level-title-name {
  font-size: 13px;
  color: #666;
  font-weight: 500;
}

.permissions-section {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
  color: #1a1a1a;
}

.permissions-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.permission-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: #f8f9fa;
  border-radius: 20px;
  font-size: 13px;
  color: #666;
}

.permission-icon {
  font-size: 14px;
}

.level-rules {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.rules-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 16px;
  color: #1a1a1a;
}

.rules-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.rule-section {
  padding: 12px;
  background: #f8f9fa;
  border-radius: 8px;
}

.rule-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
  color: #333;
}

.rule-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.rule-list li {
  font-size: 13px;
  color: #666;
  padding: 4px 10px;
  background: #fff;
  border-radius: 12px;
}
</style>
