<template>
  <div class="grade-page">
    <div class="page-header">
      <div class="header-left">
        <h1 class="page-title">创作等级权益</h1>
        <span class="page-subtitle">提升等级解锁更多创作权限</span>
      </div>
      <div class="header-right">
        <span class="rule-link" @click="showRules = true">
          <i class="el-icon-info"></i> 规则
        </span>
      </div>
    </div>

    <div class="page-content">
      <div class="section steps-section">
        <GradeSteps
          :current-power="currentPower"
          :current-level="currentLevel"
          :unlocked-count="unlockedBenefitsCount"
          :next-level-need="nextLevelNeed"
          :levels="levelConfigs"
          @click-level="handleLevelClick"
          @show-detail="openPowerDetail"
        />
      </div>

      <div class="section benefits-section">
        <BenefitCarousel
          ref="benefitCarousel"
          :title="currentLevelBenefitsTitle"
          :cards="allBenefits"
          :current-level="currentLevel"
          :selected-level="selectedLevel"
          :current-power="currentPower"
          :card-width="240"
          :visible-count="3"
        />
      </div>

      <div class="section tasks-section">
        <div class="section-header">
          <span class="section-title">如何提升等级</span>
          <span class="section-tip">完成以下任务获取掘力值</span>
        </div>
        <div class="tasks-list">
          <div
            v-for="task in growthTasks"
            :key="task.taskId"
            class="task-item"
          >
            <div class="task-icon">
              <img v-if="task.icon" :src="task.icon" :alt="task.title">
              <span v-else class="icon-fallback">{{ getTaskIcon(task.taskType) }}</span>
            </div>
            <div class="task-info">
              <div class="task-title">{{ task.title }}</div>
              <div class="task-type">{{ task.taskType }}</div>
            </div>
            <div class="task-reward">
              <span class="reward-score">+{{ task.score }}</span>
              <span class="reward-unit">掘力值</span>
            </div>
            <div class="task-limit">
              <span v-if="task.limit > 0">每日上限{{ task.limit }}次</span>
              <span v-else-if="task.limit === -1 || task.limit === null || task.limit === undefined">次数不限</span>
            </div>
            <button
              class="task-btn"
              :class="{ 'task-btn-done': task.completed }"
              @click="goToTask(task)"
            >
              {{ task.btnName || (task.completed ? '已完成' : '去完成') }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <PowerDetailModal
      :visible.sync="showDetailModal"
      :current-power="currentPower"
      :detail="powerDetail"
      :history-data="powerHistory"
    />

    <el-dialog
      :visible.sync="showRules"
      title="掘力值规则说明"
      width="600px"
      custom-class="rules-modal"
    >
      <div class="rules-content">
        <div class="rule-block">
          <div class="rule-title">什么是掘力值？</div>
          <p>掘力值是衡量创作者贡献的唯一指标，由以下四个维度构成：</p>
          <ul>
            <li><strong>创作行为：</strong>每发1篇文章+10掘力值，每日最多2篇</li>
            <li><strong>创作影响力：</strong>文章每获得1个赞/评论/收藏，每100阅读+1掘力值</li>
            <li><strong>创作质量：</strong>高质量文章+15掘力值</li>
            <li><strong>创作违规：</strong>违规行为会扣除掘力值</li>
          </ul>
        </div>
        <div class="rule-block">
          <div class="rule-title">掘力值如何更新？</div>
          <p>每日凌晨5点更新前日数据。如遇文章被删除、互动取消等情况，对应掘力值会被扣减。</p>
        </div>
        <div class="rule-block">
          <div class="rule-title">等级与权益</div>
          <p>掘力值是决定创作等级的唯一因素，不同等级解锁不同权益。当前等级权益仅展示当前等级的权益卡片。</p>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import GradeSteps from './components/GradeSteps.vue'
import BenefitCarousel from './components/BenefitCarousel.vue'
import PowerDetailModal from './components/PowerDetailModal.vue'

export default {
  name: 'CreatorGrowthGrade',
  components: {
    GradeSteps,
    BenefitCarousel,
    PowerDetailModal
  },
  data() {
    return {
      userId: this.resolveCurrentUserId(),
      currentPower: 0,
      currentLevel: 1,

      levelConfigs: [
        { level: 1, minScore: 0, maxScore: 39 },
        { level: 2, minScore: 40, maxScore: 279 },
        { level: 3, minScore: 280, maxScore: 1799 },
        { level: 4, minScore: 1800, maxScore: 5499 },
        { level: 5, minScore: 5500, maxScore: 27999 },
        { level: 6, minScore: 28000, maxScore: 74999 },
        { level: 7, minScore: 75000, maxScore: 139999 },
        { level: 8, minScore: 140000, maxScore: 99999999 }
      ],

      allBenefits: [],
      selectedLevel: 1,

      growthTasks: [],

      showDetailModal: false,
      showRules: false,

      powerDetail: {
        actionScore: 0,
        influenceScore: 0,
        qualityScore: 0,
        violationScore: 0
      },
      powerHistory: []
    }
  },
  computed: {
    unlockedBenefitsCount() {
      return this.allBenefits.filter(b => b.level <= this.currentLevel).length
    },
    nextLevelNeed() {
      const nextLevel = this.currentLevel + 1
      const nextConfig = this.levelConfigs.find(l => l.level === nextLevel)
      if (nextConfig) {
        return Math.max(0, nextConfig.minScore - this.currentPower)
      }
      return 0
    },
    currentLevelBenefitsTitle() {
      return `LV.${this.selectedLevel} 享 ${this.getLevelBenefits(this.selectedLevel).length} 项权益`
    }
  },
  created() {
    this.loadData()
  },
  methods: {
    /**
     * 从 Vuex store 读取当前登录用户 ID（修复硬编码 userId=1 导致查错用户的 bug）。
     */
    resolveCurrentUserId() {
      // 用户信息存于单 store 顶层 userInfo（Vuex 无 user 子模块）
      const u = this.$store && this.$store.state ? this.$store.state.userInfo : null
      if (u) {
        const id = u.userId || u.id
        if (id) return Number(id)
      }
      return 0
    },
    async loadData() {
      await Promise.all([
        this.loadUserLevel(),
        this.loadBenefits(),
        this.loadGrowthTasks()
      ])
    },

    async loadUserLevel() {
      try {
        const res = await this.$http.get(`/api/v1/level/user/${this.userId}/info`)
        if (res.data) {
          this.currentPower = res.data.powerValue || 0
          this.currentLevel = res.data.powerLevel || 1
          this.selectedLevel = this.currentLevel
        }
      } catch (e) {
        this.loadMockLevel()
      }
    },

    loadMockLevel() {
      this.currentPower = 13
      this.currentLevel = 1
      this.selectedLevel = 1
    },

    async loadBenefits() {
      try {
        const res = await this.$http.get('/api/v1/level/privileges')
        if (res.data) {
          const benefits = []
          Object.keys(res.data).forEach(level => {
            const levelBenefits = res.data[level]
            if (Array.isArray(levelBenefits)) {
              levelBenefits.forEach(b => {
                benefits.push({
                  privId: b.privId,
                  title: b.title,
                  icon: this.getIconUrl(b),
                  level: parseInt(level),
                  desc: typeof b.desc === 'string' ? JSON.parse(b.desc) : b.desc,
                  webJumpUrl: b.webJumpUrl || ''
                })
              })
            }
          })
          this.allBenefits = benefits
        }
      } catch (e) {
        this.loadMockBenefits()
      }
    },

    loadMockBenefits() {
      this.allBenefits = [
        { privId: 14, title: '文章添加投票', level: 1, icon: '', desc: [{ desc_content: '在进行文章创作时，可以在编辑器中使用添加投票功能' }] },
        { privId: 15, title: '文章添加视频', level: 2, icon: '', desc: [{ desc_content: '在编辑器中使用添加视频功能' }] },
        { privId: 16, title: '文章加2个标签', level: 2, icon: '', desc: [{ desc_content: '发布文章时可添加多个标签' }] },
        { privId: 17, title: '文章加3个标签', level: 3, icon: '', desc: [{ desc_content: '发布文章时可添加3个标签' }] },
        { privId: 18, title: '文章定时发布', level: 3, icon: '', desc: [{ desc_content: '设置文章定时发布时间' }] },
        { privId: 19, title: '自动推荐到首页', level: 4, icon: '', desc: [{ desc_content: '文章发布后自动推荐到首页' }] },
        { privId: 20, title: '流量加油包基础版', level: 4, icon: '', desc: [{ desc_content: '使用流量加油包加持内容曝光' }] },
        { privId: 21, title: '流量加油包升级版', level: 5, icon: '', desc: [{ desc_content: '升级版流量加油包' }] },
        { privId: 22, title: '优秀创作者', level: 5, icon: '', desc: [{ desc_content: '社区重要创作者成就' }] },
        { privId: 23, title: '流量加油包加强版', level: 6, icon: '', desc: [{ desc_content: '加强版流量加油包' }] },
        { privId: 24, title: '自定义域名', level: 6, icon: '', desc: [{ desc_content: '设置个人主页域名' }] },
        { privId: 25, title: '作者群发消息', level: 6, icon: '', desc: [{ desc_content: '给关注者群发消息' }] },
        { privId: 26, title: '创作小册', level: 7, icon: '', desc: [{ desc_content: '创作体系化的小册内容' }] },
        { privId: 27, title: '自定义推广', level: 7, icon: '', desc: [{ desc_content: '设置文章页推广模块' }] },
        { privId: 28, title: '提交标签', level: 8, icon: '', desc: [{ desc_content: '对掘金标签提供建议' }] },
        { privId: 29, title: '社区共建者', level: 8, icon: '', desc: [{ desc_content: '重磅社区成就' }] }
      ]
    },

    async loadGrowthTasks() {
      // 创作等级页的「如何提升等级」是静态引导卡（与掘金一致：规则说明，无实时进度）。
      // 注意：后端 /api/v1/level/growth-tasks 返回的是逐日等级行为配置（另一体系，字段 group_type/daily_limit），
      // 与掘力值引导任务语义不符，故此处直接用静态引导数据展示，避免结构错位导致任务区空白。
      this.buildGrowthTasks()
    },

    /**
     * 构建创作等级（掘力值）引导任务：与 ap_behavior_config 中内容创作/影响力的语义映射
     * 文案/样式对齐掘金「创作等级权益 - 如何提升等级」
     */
    buildGrowthTasks() {
      this.growthTasks = [
        { taskId: 28, taskType: '创作行为', title: '发布1篇文章', score: 10, limit: 2, btnName: '去分享', icon: '', completed: false },
        { taskId: 29, taskType: '创作影响力', title: '文章获得1个赞', score: 1, limit: -1, btnName: '去分享', icon: '', completed: false },
        { taskId: 30, taskType: '创作影响力', title: '文章获得1人评论', score: 1, limit: -1, btnName: '去分享', icon: '', completed: false },
        { taskId: 31, taskType: '创作影响力', title: '文章获得1个收藏', score: 1, limit: -1, btnName: '去分享', icon: '', completed: false },
        { taskId: 32, taskType: '创作影响力', title: '文章获得100个阅读', score: 1, limit: -1, btnName: '去分享', icon: '', completed: false }
      ]
    },

    getIconUrl(benefit) {
      if (benefit.webIcon && Array.isArray(benefit.webIcon)) {
        return benefit.webIcon[0]
      }
      return ''
    },

    getLevelBenefits(level) {
      return this.allBenefits.filter(b => b.level === level)
    },

    getTaskIcon(taskType) {
      const icons = {
        '创作行为': '✍️',
        '创作影响力': '📈',
        '创作质量': '⭐',
        '创作违规': '⚠️'
      }
      return icons[taskType] || '📌'
    },

    handleLevelClick(level) {
      this.selectedLevel = level.level
      this.$nextTick(() => {
        if (this.$refs.benefitCarousel) {
          this.$refs.benefitCarousel.jumpToLevel(level.level)
        }
      })
    },

    goToTask(task) {
      if (task.webJumpUrl) {
        this.$router.push(task.webJumpUrl)
      } else {
        this.$router.push('/creator/publish')
      }
    },

    async openPowerDetail() {
      try {
        const res = await this.$http.get(`/api/v1/level/user/${this.userId}/power-detail`)
        if (res.data) {
          this.powerDetail = {
            actionScore: res.data.actionScore || 0,
            influenceScore: res.data.influenceScore || 0,
            qualityScore: res.data.qualityScore || 0,
            violationScore: res.data.violationScore || 0
          }
          this.powerHistory = res.data.history || []
        }
      } catch (e) {
        this.loadMockPowerDetail()
      }
      this.showDetailModal = true
    },

    loadMockPowerDetail() {
      this.powerDetail = {
        actionScore: 10,
        influenceScore: 3,
        qualityScore: 0,
        violationScore: 0
      }
      this.powerHistory = [
        { date: '2026-08-04', total: 1, actionScore: 0, influenceScore: 1, qualityScore: 0, violationScore: 0 },
        { date: '2026-07-22', total: 12, actionScore: 10, influenceScore: 2, qualityScore: 0, violationScore: 0 }
      ]
    }
  }
}
</script>

<style lang="less" scoped>
@import '../layout/styles/variables.less';

.grade-page {
  min-height: 100vh;
  background: @bgGray;

  .page-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 24px 32px;
    background: #fff;
    border-bottom: 1px solid @borderLight;

    .page-title {
      font-size: 20px;
      font-weight: 600;
      color: @textPrimary;
      margin: 0 0 4px;
    }

    .page-subtitle {
      font-size: 13px;
      color: @textMuted;
    }

    .rule-link {
      font-size: 14px;
      color: @brandBlue;
      cursor: pointer;

      &:hover {
        text-decoration: underline;
      }
    }
  }

  .page-content {
    max-width: 1200px;
    margin: 0 auto;
    padding: 24px;
  }

  .section {
    background: #fff;
    border-radius: 12px;
    padding: 24px;
    margin-bottom: 24px;
    box-shadow: @cardShadow;
  }

  .tasks-section {
    .section-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 16px;

      .section-title {
        font-size: 16px;
        font-weight: 600;
        color: @textPrimary;
      }

      .section-tip {
        font-size: 13px;
        color: @textMuted;
      }
    }

    .tasks-list {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
    }

    .task-item {
      display: flex;
      align-items: center;
      padding: 16px;
      background: #f9f9f9;
      border-radius: 10px;
      transition: background 0.2s;

      &:hover {
        background: #f0f0f0;
      }

      .task-icon {
        width: 48px;
        height: 48px;
        margin-right: 16px;
        display: flex;
        align-items: center;
        justify-content: center;

        img {
          width: 100%;
          height: 100%;
          object-fit: contain;
        }

        .icon-fallback {
          font-size: 28px;
        }
      }

      .task-info {
        flex: 1;

        .task-title {
          font-size: 15px;
          font-weight: 500;
          color: @textPrimary;
          margin-bottom: 4px;
        }

        .task-type {
          font-size: 12px;
          color: @textMuted;
        }
      }

      .task-reward {
        display: flex;
        align-items: baseline;
        gap: 4px;
        margin-right: 20px;

        .reward-score {
          font-size: 18px;
          font-weight: 600;
          color: #ff6b35;
        }

        .reward-unit {
          font-size: 12px;
          color: @textMuted;
        }
      }

      .task-limit {
        font-size: 12px;
        color: @textMuted;
        margin-right: 16px;
      }

      .task-btn {
        padding: 6px 16px;
        font-size: 13px;
        color: #fff;
        border: none;
        border-radius: 4px;
        background: linear-gradient(135deg, #7C3AED 0%, #5B21B6 100%);
        cursor: pointer;
        transition: all 0.2s;

        &:hover {
          opacity: 0.88;
          box-shadow: 0 2px 8px rgba(124, 58, 237, 0.25);
        }

        &.task-btn-done {
          color: @textMuted;
          background: #E5E7EB;
          cursor: default;
          box-shadow: none;
        }
      }
    }
  }
}

.rules-modal {
  .rules-content {
    .rule-block {
      margin-bottom: 20px;

      .rule-title {
        font-size: 15px;
        font-weight: 600;
        color: @textPrimary;
        margin-bottom: 8px;
      }

      p {
        font-size: 13px;
        color: @textSecondary;
        line-height: 1.6;
        margin: 0 0 8px;
      }

      ul {
        margin: 0;
        padding-left: 20px;

        li {
          font-size: 13px;
          color: @textSecondary;
          line-height: 1.8;
        }
      }
    }
  }
}

@media screen and (max-width: 768px) {
  .grade-page {
    .page-header {
      padding: 16px;
      flex-direction: column;
      align-items: flex-start;
      gap: 12px;
    }

    .page-content {
      padding: 16px;
    }

    .section {
      padding: 16px;
    }

    .tasks-section {
      .task-item {
        flex-wrap: wrap;
        padding: 12px;

        .task-reward {
          margin-right: 12px;
        }

        .task-btn {
          width: 100%;
          margin-top: 8px;
        }
      }
    }
  }
}
</style>
