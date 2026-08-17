<template>
  <div class="booklet-station">
    <!-- 作者简介卡 -->
    <div class="station-profile-card">
      <div class="profile-left">
        <div class="avatar">
          <img v-if="profileAvatar" :src="profileAvatar" alt="avatar" />
          <i v-else class="el-icon-user"></i>
        </div>
        <div class="profile-info">
          <div class="username">{{ profile.realName || '作家' }}</div>
          <div class="position">{{ profile.position || '分享技术，沉淀知识' }}</div>
          <div class="intro" v-if="profile.personalIntro">{{ profile.personalIntro }}</div>
        </div>
      </div>
      <div class="profile-right">
        <a class="entry-link" v-if="booklets.length > 0" :href="`/booklet/edit?courseId=${lastBookletId}`" target="_blank">
          <el-button type="primary" icon="el-icon-edit" size="small">写作</el-button>
        </a>
        <el-button v-else type="primary" icon="el-icon-edit" size="small" @click="goApply">开始写作</el-button>
      </div>
    </div>

    <!-- 数据占位卡：当日销量 / 总销量 / 发出结算 / 流水 -->
    <div class="station-stats">
      <div class="stat-card">
        <div class="stat-label">当日销量</div>
        <div class="stat-value">—</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">总销量</div>
        <div class="stat-value">—</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">小册流水</div>
        <div class="stat-value">—</div>
      </div>
      <div class="stat-card clickable" @click="onSettle">
        <div class="stat-label">发起结算</div>
        <div class="stat-value stat-action">去结算 ›</div>
      </div>
    </div>

    <!-- 我的小册列表 -->
    <div class="station-section">
      <div class="section-header">
        <h3 class="section-title">我的小册</h3>
        <el-button type="text" icon="el-icon-plus" @click="goApply">申请成为作家</el-button>
      </div>

      <div class="booklet-empty" v-if="!loading && booklets.length === 0">
        <i class="el-icon-notebook-2"></i>
        <p>还没有小册，点击「申请成为作家」开始创作吧</p>
      </div>

      <div class="booklet-card" v-for="b in booklets" :key="b.id">
        <div class="card-cover">
          <img v-if="b.coverImage" :src="b.coverImage" :alt="b.title" />
          <i v-else class="el-icon-notebook-2"></i>
        </div>
        <div class="card-body">
          <div class="card-title-row">
            <span class="card-title">{{ b.title || '未命名小册' }}</span>
            <el-tag :type="statusType(b.status)" size="small" class="status-tag">
              {{ statusText(b.status) }}
            </el-tag>
          </div>
          <div class="card-desc" v-if="b.description">{{ b.description }}</div>
          <div class="card-reject" v-if="b.status === 2">
            <span class="reject-reason">拒绝原因：{{ b.reason || b.applyReason || '未填写' }}</span>
            <el-button type="text" size="small" class="re-apply" @click="reApply(b)">重新申请</el-button>
          </div>
          <div class="card-meta">
            <span>读量 <b>{{ b.studyCount || 0 }}</b></span>
            <span class="meta-divider">·</span>
            <span>订阅量 <b>{{ b.salesCount || 0 }}</b></span>
          </div>
        </div>
        <div class="card-actions">
          <el-button type="primary" size="small" @click="openManage(b)">管理维护</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import courseApi from '@/apis/course'
import { mapGetters } from 'vuex'

const STATUS_MAP = {
  0: '草稿',
  1: '申请中',
  2: '申请失败',
  3: '维护中',
  4: '正常',
  5: '预售',
  9: '在售'
}

const STATUS_TYPE = {
  0: 'info',
  1: 'warning',
  2: 'danger',
  3: 'info',
  4: '',
  5: 'warning',
  9: 'success'
}

export default {
  name: 'BookletStation',
  computed: {
    ...mapGetters(['userInfo']),
    profileAvatar() {
      return (this.userInfo && this.userInfo.headImage) || ''
    },
    lastBookletId() {
      // 写作入口默认打开最近更新的一本小册
      return this.booklets.length > 0 ? this.booklets[0].id : null
    }
  },
  data() {
    return {
      booklets: [],
      loading: false,
      profile: {}
    }
  },
  mounted() {
    this.loadProfile()
    this.loadBooklets()
  },
  methods: {
    statusText(s) {
      return STATUS_MAP[s] || '未知'
    },
    statusType(s) {
      return STATUS_TYPE[s] || 'info'
    },
    async loadProfile() {
      try {
        const res = await courseApi.getAuthorProfile()
        if (res && res.code === 200 && res.data) {
          this.profile = res.data
        }
      } catch (e) {
        // 静默
      }
    },
    async loadBooklets() {
      this.loading = true
      try {
        const res = await courseApi.getMyBooklets({ page: 1, size: 100 })
        if (res && res.code === 200 && res.data) {
          this.booklets = res.data.list || []
        }
      } catch (e) {
        this.$message.error('加载小册列表失败')
      } finally {
        this.loading = false
      }
    },
    goApply() {
      this.$router.push('/booklet/apply')
    },
    reApply(b) {
      this.$router.push({ path: '/booklet/apply', query: { courseId: b.id } })
    },
    openManage(b) {
      window.open(`/booklet/manage?courseId=${b.id}`, '_blank')
    },
    onSettle() {
      this.$message.info('发起结算功能后续开放')
    }
  }
}
</script>

<style lang="less" scoped>
.booklet-station {
  padding: 20px;

  .station-profile-card {
    display: flex;
    align-items: center;
    justify-content: space-between;
    background: #fff;
    border-radius: 12px;
    padding: 20px 24px;
    margin-bottom: 16px;

    .profile-left {
      display: flex;
      align-items: center;
      gap: 16px;

      .avatar {
        width: 56px;
        height: 56px;
        border-radius: 50%;
        background: #f0f1f5;
        display: flex;
        align-items: center;
        justify-content: center;
        overflow: hidden;
        font-size: 24px;
        color: #999;
        img {
          width: 100%;
          height: 100%;
          object-fit: cover;
        }
      }
      .profile-info {
        .username {
          font-size: 17px;
          font-weight: 600;
          color: #252933;
        }
        .position {
          font-size: 13px;
          color: #515767;
          margin-top: 2px;
        }
        .intro {
          font-size: 13px;
          color: #8a919f;
          margin-top: 4px;
          max-width: 420px;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }
    }
  }

  .station-stats {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 14px;
    margin-bottom: 20px;

    .stat-card {
      background: #fff;
      border-radius: 12px;
      padding: 18px;
      text-align: center;

      .stat-label {
        font-size: 13px;
        color: #8a919f;
        margin-bottom: 8px;
      }
      .stat-value {
        font-size: 22px;
        font-weight: 700;
        color: #252933;
      }
      .stat-action {
        font-size: 15px;
        color: #1e80ff;
        cursor: pointer;
      }
      &.clickable {
        cursor: pointer;
      }
    }
  }

  .station-section {
    background: #fff;
    border-radius: 12px;
    padding: 20px 24px;

    .section-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 16px;
      .section-title {
        font-size: 16px;
        font-weight: 600;
        color: #252933;
      }
    }

    .booklet-empty {
      text-align: center;
      padding: 48px 0;
      color: #8a919f;
      font-size: 14px;
      .el-icon-notebook-2 {
        font-size: 40px;
        color: #c0c4cc;
        display: block;
        margin-bottom: 12px;
      }
    }

    .booklet-card {
      display: flex;
      align-items: center;
      border: 1px solid #f0f1f5;
      border-radius: 12px;
      padding: 16px;
      margin-bottom: 12px;
      &:last-child {
        margin-bottom: 0;
      }

      .card-cover {
        width: 72px;
        height: 96px;
        border-radius: 8px;
        background: #f0f1f5;
        flex-shrink: 0;
        overflow: hidden;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 28px;
        color: #c0c4cc;
        img {
          width: 100%;
          height: 100%;
          object-fit: cover;
        }
      }

      .card-body {
        flex: 1;
        margin-left: 16px;
        overflow: hidden;

        .card-title-row {
          display: flex;
          align-items: center;
          gap: 8px;
          .card-title {
            font-size: 15px;
            font-weight: 600;
            color: #252933;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
          }
          .status-tag {
            flex-shrink: 0;
          }
        }
        .card-desc {
          font-size: 13px;
          color: #8a919f;
          margin-top: 6px;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        .card-reject {
          margin-top: 6px;
          font-size: 13px;
          color: #f56c6c;
          display: flex;
          align-items: center;
          .re-apply {
            margin-left: 8px;
          }
        }
        .card-meta {
          margin-top: 8px;
          font-size: 13px;
          color: #515767;
          b {
            color: #252933;
          }
          .meta-divider {
            margin: 0 6px;
            color: #c0c4cc;
          }
        }
      }

      .card-actions {
        flex-shrink: 0;
        margin-left: 16px;
      }
    }
  }
}
</style>