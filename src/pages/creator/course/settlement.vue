<template>
  <div class="settlement-page">
    <div class="page-header">
      <h2 class="page-title">收入结算</h2>
    </div>

    <div class="settlement-summary" v-if="!loading">
      <div class="summary-card">
        <div class="summary-label">累计销售额</div>
        <div class="summary-value">¥{{ totalSales.toFixed(2) }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">平台分成 (30%)</div>
        <div class="summary-value sub">¥{{ platformShare.toFixed(2) }}</div>
      </div>
      <div class="summary-card highlight">
        <div class="summary-label">作者收入 (70%)</div>
        <div class="summary-value">¥{{ authorShare.toFixed(2) }}</div>
      </div>
      <div class="summary-card tip-card">
        <div class="summary-label">文章打赏收入</div>
        <div class="summary-value">¥{{ tipRevenue.toFixed(2) }}</div>
        <div class="summary-tip-count">{{ tipCount }} 次赞赏</div>
      </div>
    </div>

    <div class="settlement-table" v-loading="loading">
      <el-table :data="settlementList" style="width: 100%" v-if="settlementList.length > 0">
        <el-table-column prop="settlementMonth" label="结算月份" width="120" />
        <el-table-column prop="courseTitle" label="课程" min-width="180" />
        <el-table-column label="销售额" width="120">
          <template slot-scope="scope">
            ¥{{ scope.row.totalSales }}
          </template>
        </el-table-column>
        <el-table-column label="订单数" width="80">
          <template slot-scope="scope">
            {{ scope.row.orderCount }}
          </template>
        </el-table-column>
        <el-table-column label="平台分成" width="120">
          <template slot-scope="scope">
            ¥{{ scope.row.platformShare }}
          </template>
        </el-table-column>
        <el-table-column label="作者收入" width="120">
          <template slot-scope="scope">
            <span class="income-value">¥{{ scope.row.authorShare }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template slot-scope="scope">
            <el-tag :type="scope.row.status === 1 ? 'success' : 'warning'" size="small">
              {{ scope.row.status === 1 ? '已结算' : '待结算' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结算时间" width="160">
          <template slot-scope="scope">
            {{ scope.row.settledAt || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template slot-scope="scope">
            <el-button type="text" size="small" @click="showDetail(scope.row)">明细</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="empty-state" v-if="!loading && settlementList.length === 0">
        <i class="el-icon-money"></i>
        <p>暂无结算记录</p>
      </div>
    </div>

    <!-- 结算明细弹窗 -->
    <el-dialog
      title="结算明细"
      :visible.sync="showDetailDialog"
      width="500px"
    >
      <div class="detail-content" v-if="currentDetail">
        <div class="detail-row">
          <span class="detail-label">结算月份</span>
          <span class="detail-value">{{ currentDetail.settlementMonth }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">课程</span>
          <span class="detail-value">{{ currentDetail.courseTitle }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">订单数</span>
          <span class="detail-value">{{ currentDetail.orderCount }} 笔</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">总销售额</span>
          <span class="detail-value">¥{{ currentDetail.totalSales }}</span>
        </div>
        <el-divider />
        <div class="detail-row">
          <span class="detail-label">平台分成 (30%)</span>
          <span class="detail-value">¥{{ currentDetail.platformShare }}</span>
        </div>
        <div class="detail-row highlight">
          <span class="detail-label">作者收入 (70%)</span>
          <span class="detail-value income">¥{{ currentDetail.authorShare }}</span>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import courseApi from '@/apis/course'

export default {
  name: 'CreatorCourseSettlement',
  data() {
    return {
      loading: true,
      settlementList: [],
      totalSales: 0,
      platformShare: 0,
      authorShare: 0,
      tipRevenue: 0,
      tipCount: 0,
      showDetailDialog: false,
      currentDetail: null
    }
  },
  mounted() {
    this.loadSettlements()
  },
  methods: {
    async loadSettlements() {
      this.loading = true
      try {
        const res = await courseApi.getSettlementMonthly()
        if (res && res.code === 200 && res.data) {
          this.settlementList = res.data.list || []
          this.totalSales = res.data.totalSales || 0
          this.platformShare = res.data.totalPlatformShare || 0
          this.authorShare = res.data.totalAuthorShare || 0
        }
        // 文章打赏收益
        const tipRes = await courseApi.getTipRevenue()
        if (tipRes && tipRes.code === 200 && tipRes.data) {
          this.tipRevenue = tipRes.data.totalAmount || 0
          this.tipCount = tipRes.data.totalCount || 0
        }
      } catch (e) {
        console.error('加载结算记录失败', e)
      } finally {
        this.loading = false
      }
    },
    showDetail(row) {
      this.currentDetail = row
      this.showDetailDialog = true
    }
  }
}
</script>

<style lang="less" scoped>
.settlement-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #333;
  margin: 0;
}

.settlement-summary {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
}

.summary-card {
  flex: 1;
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  text-align: center;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}

.summary-card.highlight {
  background: linear-gradient(135deg, #1E80FF, #4A90FF);
  .summary-label, .summary-value { color: #fff; }
}

.summary-card.tip-card {
  background: linear-gradient(135deg, #ff8a3d, #ff6b00);
  .summary-label, .summary-value { color: #fff; }
  .summary-tip-count { color: rgba(255,255,255,0.85); font-size: 12px; margin-top: 4px; }
}

.summary-label {
  font-size: 13px;
  color: #999;
  margin-bottom: 8px;
}

.summary-value {
  font-size: 24px;
  font-weight: 700;
  color: #333;
}

.summary-value.sub {
  font-size: 20px;
  color: #666;
}

.settlement-table {
  background: #fff;
  border-radius: 8px;
  min-height: 200px;
}

.income-value {
  color: #1E80FF;
  font-weight: 600;
}

.empty-state {
  text-align: center;
  padding: 60px 0;
  color: #999;
  i {
    font-size: 48px;
    margin-bottom: 12px;
  }
  p {
    font-size: 14px;
    margin-bottom: 16px;
  }
}

.detail-content {
  padding: 8px 0;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
}

.detail-row.highlight {
  background: #f0f8ff;
  margin: 0 -20px;
  padding: 10px 20px;
  border-radius: 4px;
}

.detail-label {
  font-size: 14px;
  color: #666;
}

.detail-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.detail-value.income {
  color: #1E80FF;
  font-size: 18px;
  font-weight: 700;
}
</style>