<template>
  <div class="pins-manage">
    <div class="header-bar">
      <div class="page-title">沸点</div>
      <el-button type="primary" @click="showPublishModal = true">发布沸点</el-button>
    </div>

    <div class="status-tabs">
      <el-tag
        v-for="tab in statusTabs"
        :key="tab.value"
        :type="activeStatus === tab.value ? 'primary' : 'info'"
        :class="{ 'active-tab': activeStatus === tab.value }"
        @click="changeStatus(tab.value)"
      >{{ tab.label }} ({{ tab.count }})</el-tag>
    </div>

    <div v-if="pinsList.length === 0" class="empty-state">
      <div class="empty-icon">💬</div>
      <div class="empty-text">这里什么都没有</div>
      <el-button type="primary" @click="showPublishModal = true">发布沸点</el-button>
    </div>

    <div v-else class="pins-list">
      <div v-for="pins in pinsList" :key="pins.id" class="pins-item">
        <div class="pins-content">
          <div class="pins-text">{{ pins.content }}</div>
          <div class="pins-tags">
            <span v-if="pins.circleName" class="pins-circle">{{ pins.circleName }}</span>
            <span v-if="pins.topicName" class="pins-topic">{{ pins.topicName }}</span>
          </div>
          <div class="pins-meta">
            <span>{{ formatTime(pins.createdTime) }}</span>
            <span class="status-tag" :class="getStatusClass(pins.status)">{{ getStatusLabel(pins.status) }}</span>
          </div>
        </div>
        <div class="pins-actions">
          <el-dropdown trigger="click" @command="(cmd) => operatePins(pins.id, cmd)">
            <span class="el-dropdown-link"><i class="el-icon-more"></i></span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="del">删除</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </div>
      </div>
    </div>

    <div class="pagination" v-if="total > 0">
      <el-pagination
        layout="total, prev, pager, next"
        @current-change='pageChange'
        :current-page.sync='currentPage'
        :page-size="pageSize"
        :total="total"
      />
    </div>

    <PinsPublishModal
      v-if="showPublishModal"
      v-model="publishContent"
      :selectedCircle="selectedCircle"
      :selectedTopic="selectedTopic"
      :publishing="publishing"
      @close="closePublishModal"
      @select-circle="showCircleSelector = true"
      @select-topic="showTopicSelector = true"
      @publish="publishPins"
      @update:selectedTopic="selectedTopic = $event"
    />

    <PinsCircleSelector
      v-if="showCircleSelector"
      :circles="circles"
      :selected="selectedCircle"
      @close="showCircleSelector = false"
      @select="selectCircle"
      @confirm="onCircleConfirm"
    />

    <PinsTopicSelector
      v-if="showTopicSelector"
      :topics="topics"
      :selected="selectedTopic"
      @close="showTopicSelector = false"
      @select="selectTopic"
    />
  </div>
</template>

<script>
import { getPinsList, getPinsStatistics, createPins, deletePins } from '@/apis/creator/content'
import PinsPublishModal from './components/PinsPublishModal.vue'
import PinsCircleSelector from './components/PinsCircleSelector.vue'
import PinsTopicSelector from './components/PinsTopicSelector.vue'

export default {
  name: 'PinsManage',
  components: { PinsPublishModal, PinsCircleSelector, PinsTopicSelector },
  data() {
    return {
      pinsList: [],
      total: 0,
      currentPage: 1,
      pageSize: 10,
      activeStatus: 'all',
      showPublishModal: false,
      showCircleSelector: false,
      showTopicSelector: false,
      publishContent: '',
      selectedCircle: null,
      selectedTopic: null,
      publishing: false,
      statusTabs: [
        { label: '全部', value: 'all', count: 0 },
        { label: '已发布', value: 'published', count: 0 },
        { label: '审核中', value: 'reviewing', count: 0 },
        { label: '未通过', value: 'rejected', count: 0 }
      ],
      circles: [
        { id: 13, name: '大模型生态圈', memberCount: 35000, pinsCount: 80000, icon: '🤖' },
        { id: 14, name: '微服务生态圈', memberCount: 25000, pinsCount: 60000, icon: '🏗️' },
        { id: 15, name: '前端开发圈', memberCount: 40000, pinsCount: 120000, icon: '🎨' },
        { id: 16, name: '服务端与架构', memberCount: 30000, pinsCount: 70000, icon: '⚙️' },
        { id: 17, name: '技术交流圈', memberCount: 50000, pinsCount: 150000, icon: '💬' },
        { id: 18, name: '上班摸鱼', memberCount: 40000, pinsCount: 100000, icon: '🐟' },
        { id: 19, name: '内推招聘广场', memberCount: 35000, pinsCount: 80000, icon: '📋' },
        { id: 20, name: '美食探店', memberCount: 50000, pinsCount: 150000, icon: '🍜' }
      ],
      topics: [
        { id: 1, name: '#新人报道#', count: 5000 },
        { id: 2, name: '#程序员脱单到底有多难#', count: 15000 },
        { id: 3, name: '#每日快讯#', count: 20000 },
        { id: 4, name: '#每日精选文章#', count: 18000 },
        { id: 5, name: '#日新计划#', count: 12000 },
        { id: 6, name: '#每天一个知识点#', count: 8000 },
        { id: 7, name: '#代码人生#', count: 10000 },
        { id: 8, name: '#优秀开源项目#', count: 7000 },
        { id: 9, name: '#技术交流#', count: 13000 },
        { id: 10, name: '#上班摸鱼#', count: 25000 }
      ]
    }
  },
  created() {
    this.loadData()
  },
  methods: {
    async loadData() {
      await this.loadStatistics()
      await this.loadList()
    },
    async loadStatistics() {
      const res = await getPinsStatistics()
      if (res && res.code === 200 && res.data) {
        const data = res.data
        this.statusTabs = [
          { label: '全部', value: 'all', count: (data.published || 0) + (data.reviewing || 0) + (data.rejected || 0) },
          { label: '已发布', value: 'published', count: data.published || 0 },
          { label: '审核中', value: 'reviewing', count: data.reviewing || 0 },
          { label: '未通过', value: 'rejected', count: data.rejected || 0 }
        ]
      }
    },
    async loadList() {
      const params = {
        page: this.currentPage,
        size: this.pageSize,
        status: this.activeStatus === 'all' ? '' : this.activeStatus
      }
      const res = await getPinsList(params)
      if (res && res.code === 200 && res.data) {
        this.pinsList = res.data.list || []
        this.total = res.data.total || 0
      }
    },
    changeStatus(status) {
      this.activeStatus = status
      this.currentPage = 1
      this.loadList()
    },
    pageChange(newPage) {
      this.currentPage = newPage
      this.loadList()
    },
    selectCircle(circle) {
      this.selectedCircle = circle
    },
    onCircleConfirm(circle) {
      if (circle) {
        this.selectedCircle = circle
      }
      this.showCircleSelector = false
    },
    selectTopic(topic) {
      this.selectedTopic = topic
      this.showTopicSelector = false
    },
    closePublishModal() {
      this.showPublishModal = false
      this.publishContent = ''
    },
    async publishPins(data) {
      if (!data.content.trim()) return
      this.publishing = true
      const res = await createPins(data)
      if (res && res.code === 200) {
        this.$message.success('发布成功')
        this.showPublishModal = false
        this.publishContent = ''
        this.selectedCircle = null
        this.selectedTopic = null
        this.loadData()
      } else {
        this.$message.error(res?.errorMessage || '发布失败')
      }
      this.publishing = false
    },
    operatePins(id, type) {
      if (type === 'del') {
        this.$confirm('确定要删除该沸点吗？', '提示', { type: 'warning' }).then(() => {
          this.doDelete(id)
        })
      }
    },
    async doDelete(id) {
      const res = await deletePins(id)
      if (res && res.code === 200) {
        this.$message.success('删除成功')
        this.loadData()
      } else {
        this.$message.error(res?.errorMessage || '删除失败')
      }
    },
    formatTime(time) {
      if (!time) return ''
      return new Date(time).toLocaleString('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      })
    },
    getStatusLabel(status) {
      const map = { '0': '草稿', '1': '审核中', '2': '未通过', '9': '已发布' }
      return map[status] || '未知'
    },
    getStatusClass(status) {
      const map = { '0': 'status-draft', '1': 'status-reviewing', '2': 'status-rejected', '9': 'status-published' }
      return map[status] || ''
    }
  }
}
</script>

<style lang="less" scoped>
@import '../layout/styles/variables.less';

.pins-manage {
  min-height: calc(100vh - 50px);
  background-color: @bgGray;
  padding: 20px;

  .header-bar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;

    .page-title {
      font-size: 20px;
      font-weight: 500;
      color: @textPrimary;
    }
  }

  .status-tabs {
    margin-bottom: 16px;
    .el-tag {
      cursor: pointer;
      margin-right: 8px;
      padding: 4px 16px;
      &.active-tab {
        background-color: #1e80ff;
        border-color: #1e80ff;
        color: #fff;
      }
    }
  }

  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 80px 0;

    .empty-icon {
      font-size: 64px;
      margin-bottom: 16px;
    }

    .empty-text {
      font-size: 14px;
      color: #909399;
      margin-bottom: 24px;
    }
  }

  .pins-list {
    background-color: #fff;
    border-radius: 8px;
    padding: 16px;

    .pins-item {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      padding: 16px 0;
      border-bottom: 1px solid #f0f0f0;
      &:last-child { border-bottom: none; }

      .pins-content {
        flex: 1;

        .pins-text {
          font-size: 14px;
          color: #303133;
          line-height: 1.6;
          margin-bottom: 10px;
          word-break: break-word;
        }

        .pins-tags {
          display: flex;
          gap: 8px;
          margin-bottom: 10px;

          .pins-circle {
            padding: 2px 8px;
            background: #eaf2ff;
            color: #1e80ff;
            font-size: 12px;
            border-radius: 4px;
          }

          .pins-topic {
            padding: 2px 8px;
            background: #fff7e6;
            color: #fa8c16;
            font-size: 12px;
            border-radius: 4px;
          }
        }

        .pins-meta {
          display: flex;
          align-items: center;
          font-size: 12px;
          color: #909399;

          .status-tag {
            margin-left: 12px;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 11px;
          }

          .status-draft { background: #f5f5f5; color: #999; }
          .status-reviewing { background: #fff7e6; color: #d48806; }
          .status-rejected { background: #fef0f0; color: #f56c6c; }
          .status-published { background: #f0f9eb; color: #67c23a; }
        }
      }

      .pins-actions {
        .el-dropdown-link {
          cursor: pointer;
          color: #909399;
          &:hover { color: #1e80ff; }
        }
      }
    }
  }

  .pagination {
    text-align: right;
    margin-top: 20px;
  }
}
</style>