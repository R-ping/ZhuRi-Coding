<template>
  <section class="dashboard-stats">
    <header class="section-header">
      <span>数据概览</span>
      <a class="more-link" href="javascript:;" @click="goToData">查看更多 ></a>
    </header>
    <div class="stats-grid">
      <div class="stat-card" v-for="item in stats" :key="item.label">
        <div class="stat-value">{{ item.value }}</div>
        <div class="stat-label">{{ item.label }}</div>
        <div class="stat-compare" :class="item.trendClass">较前日 {{ item.trendText }}</div>
      </div>
    </div>
  </section>
</template>

<script>
import { getArticleStatistics } from '@/apis/creator/data.js'
import { getFansStatistics } from '@/apis/creator/fans.js'

export default {
  data() {
    return {
      stats: [
        { label: '总粉丝数', value: 0, trendText: '--', trendClass: '' },
        { label: '文章展现数', value: 0, trendText: '--', trendClass: '' },
        { label: '文章阅读数', value: 0, trendText: '--', trendClass: '' },
        { label: '文章点赞数', value: 0, trendText: '--', trendClass: '' },
        { label: '文章评论数', value: 0, trendText: '--', trendClass: '' },
        { label: '文章收藏数', value: 0, trendText: '--', trendClass: '' }
      ]
    }
  },
  created() {
    this.fetchData()
  },
  methods: {
    async fetchData() {
      try {
        const today = this.getDateStr()
        // 并行获取文章统计和粉丝统计
        const [articleRes, fansRes] = await Promise.all([
          getArticleStatistics({ startDate: today, endDate: today }),
          getFansStatistics({ startDate: today, endDate: today })
        ])

        if (articleRes && articleRes.code === 200 && articleRes.data) {
          const d = articleRes.data
          this.stats = this.stats.map(item => {
            switch (item.label) {
              case '文章展现数':
                return { ...item, value: d.showCount || 0, trendText: this.formatTrend(d.showTrend), trendClass: this.getTrendClass(d.showTrend) }
              case '文章阅读数':
                return { ...item, value: d.readCount || 0, trendText: this.formatTrend(d.readTrend), trendClass: this.getTrendClass(d.readTrend) }
              case '文章点赞数':
                return { ...item, value: d.likeCount || 0, trendText: this.formatTrend(d.likeTrend), trendClass: this.getTrendClass(d.likeTrend) }
              case '文章评论数':
                return { ...item, value: d.commentCount || 0, trendText: this.formatTrend(d.commentTrend), trendClass: this.getTrendClass(d.commentTrend) }
              case '文章收藏数':
                return { ...item, value: d.collectCount || 0, trendText: this.formatTrend(d.collectTrend), trendClass: this.getTrendClass(d.collectTrend) }
              default:
                return item
            }
          })
        }

        if (fansRes && fansRes.code === 200 && fansRes.data) {
          const d = fansRes.data
          this.stats = this.stats.map(item => {
            if (item.label === '总粉丝数') {
              return { ...item, value: d.totalFans || 0, trendText: this.formatTrend(d.totalTrend), trendClass: this.getTrendClass(d.totalTrend) }
            }
            return item
          })
        }
      } catch (err) {
        console.error('获取数据概览失败', err)
      }
    },
    formatTrend(val) {
      if (val === undefined || val === null) return '--'
      if (val > 0) return '+' + val
      return String(val)
    },
    getTrendClass(val) {
      if (val === undefined || val === null) return ''
      if (val > 0) return 'trend-up'
      if (val < 0) return 'trend-down'
      return 'trend-flat'
    },
    getDateStr() {
      const d = new Date()
      const y = d.getFullYear()
      const m = String(d.getMonth() + 1).padStart(2, '0')
      const day = String(d.getDate()).padStart(2, '0')
      return y + '-' + m + '-' + day
    },
    goToData() {
      this.$router.push('/creator/data')
    }
  }
}
</script>

<style lang="less" scoped>
  @import '../../layout/styles/variables.less';

  .dashboard-stats {
    display: flex;
    flex-direction: column;

    .section-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 20px;
      font-size: 16px;
      font-weight: 600;
      color: @textPrimary;
      border-bottom: 1px solid #f2f3f5;
      flex-shrink: 0;

      .more-link {
        font-size: 13px;
        font-weight: 400;
        color: @textMuted;
        text-decoration: none;
        cursor: pointer;

        &:hover {
          color: @brandBlue;
        }
      }
    }

    .stats-grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 10px;
      padding: 12px 16px;
    }

    .stat-card {
      padding: 10px 14px;
      background: #F7F8FA;
      border: 1px solid transparent;
      border-radius: 8px;
      transition: all 0.2s ease;

      &:hover {
        border-color: @brandBlue;
        background: #F0F6FF;
        transform: translateY(-1px);
      }

      .stat-value {
        font-size: 20px;
        font-weight: 700;
        color: @colorStatValue;
        margin-bottom: 2px;
        line-height: 1.2;
      }

      .stat-label {
        font-size: 12px;
        color: @textSecondary;
        margin-bottom: 2px;
        font-weight: 400;
      }

      .stat-compare {
        font-size: 12px;
        color: @colorStatLabel;
        font-weight: 400;

        &.trend-up {
          color: #f56c6c;
        }

        &.trend-down {
          color: #67c23a;
        }

        &.trend-flat {
          color: @colorStatLabel;
        }
      }
    }
  }
</style>