<template>
  <div class="content-manage">
    <div class="header-bar">
      <div class="tabs">
        <span :class="{ active: activeTab === 'article' }" @click="activeTab = 'article'">文章</span>
        <span :class="{ active: activeTab === 'draft' }" @click="activeTab = 'draft'">草稿箱 ({{ draftCount }})</span>
      </div>
      <div class="search-box">
        <el-input
          v-model="searchText"
          placeholder="请输入标题关键字"
          prefix-icon="el-icon-search"
          clearable
          @keyup.enter.native="handleSearch"
        />
      </div>
    </div>

    <div v-if="activeTab === 'article'" class="status-tabs">
      <el-tag
        v-for="tab in articleStatusTabs"
        :key="tab.value"
        :type="activeStatus === tab.value ? 'primary' : 'info'"
        :class="{ 'active-tab': activeStatus === tab.value }"
        @click="changeStatus(tab.value)"
      >{{ tab.label }} ({{ tab.count }})</el-tag>
    </div>

    <div v-if="activeTab === 'article' && articleList.length === 0" class="empty-state">
      <div class="empty-icon">✏️</div>
      <div class="empty-text">这里什么都没有</div>
      <el-button type="primary" @click="goToPublish">开始创作</el-button>
    </div>

    <div v-if="activeTab === 'article' && articleList.length > 0" class="article-list">
      <div v-for="article in articleList" :key="article.id" class="article-item">
        <div class="article-content">
          <div class="article-title">{{ article.title || '无标题' }}</div>
          <div class="article-meta">
            <span>{{ formatTime(article.createdTime) }}</span>
            <span class="status-tag" :class="getStatusClass(article.status)">{{ getStatusLabel(article.status) }}</span>
          </div>
        </div>
        <div class="article-actions">
          <el-dropdown trigger="click" @command="(cmd) => operateArticle(article.id, cmd)">
            <span class="el-dropdown-link"><i class="el-icon-more"></i></span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="edit">编辑</el-dropdown-item>
              <el-dropdown-item command="del">删除</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </div>
      </div>
    </div>

    <div v-if="activeTab === 'draft' && draftList.length === 0" class="empty-state">
      <div class="empty-icon">📝</div>
      <div class="empty-text">暂无草稿</div>
      <el-button type="primary" @click="goToPublish">开始创作</el-button>
    </div>

    <div v-if="activeTab === 'draft' && draftList.length > 0" class="article-list">
      <div v-for="draft in draftList" :key="draft.id" class="article-item">
        <div class="article-content">
          <div class="article-title">{{ draft.title || '无标题' }}</div>
          <div class="article-meta">
            <span>{{ formatTime(draft.updatedTime) }}</span>
          </div>
        </div>
        <div class="article-actions">
          <el-dropdown trigger="click" @command="(cmd) => operateDraft(draft.id, cmd)">
            <span class="el-dropdown-link"><i class="el-icon-more"></i></span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="edit">编辑</el-dropdown-item>
              <el-dropdown-item command="del">删除</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </div>
      </div>
    </div>

    <div class="pagination" v-if="total > 0">
      <el-pagination
        layout="total, prev, pager, next"
        :total="total"
        :current-page.sync="currentPage"
        :page-size="pageSize"
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<script>
import { getArticleList, getArticleStatistics, deleteArticle, getDraftList, deleteDraft } from '@/apis/creator/content'

export default {
  name: 'ContentManage',
  data() {
    return {
      activeTab: 'article',
      activeStatus: 'all',
      searchText: '',
      articleList: [],
      draftList: [],
      draftCount: 0,
      total: 0,
      currentPage: 1,
      pageSize: 10,
      articleStatusTabs: [
        { label: '全部', value: 'all', count: 0 },
        { label: '已发布', value: 'published', count: 0 },
        { label: '审核中', value: 'reviewing', count: 0 },
        { label: '未通过', value: 'rejected', count: 0 }
      ]
    }
  },
  created() {
    this.loadData()
  },
  watch: {
    activeTab() {
      this.currentPage = 1
      this.loadData()
    }
  },
  methods: {
    async loadData() {
      if (this.activeTab === 'article') {
        await this.loadArticleStatistics()
        await this.loadArticleList()
        await this.loadDraftCount()
      } else {
        await this.loadDraftList()
      }
    },
    async loadDraftCount() {
      try {
        const res = await getDraftList({ page: 1, size: 1 })
        if (res && res.code === 200 && res.data) {
          this.draftCount = res.data.total || 0
        }
      } catch (e) {
        // 静默处理
      }
    },
    async loadArticleStatistics() {
      const res = await getArticleStatistics()
      if (res && res.code === 200 && res.data) {
        const data = res.data
        this.articleStatusTabs = [
          { label: '全部', value: 'all', count: (data.published || 0) + (data.reviewing || 0) + (data.rejected || 0) },
          { label: '已发布', value: 'published', count: data.published || 0 },
          { label: '审核中', value: 'reviewing', count: data.reviewing || 0 },
          { label: '未通过', value: 'rejected', count: data.rejected || 0 }
        ]
      }
    },
    async loadArticleList() {
      const params = {
        page: this.currentPage,
        size: this.pageSize,
        status: this.activeStatus === 'all' ? '' : this.activeStatus,
        title: this.searchText || ''
      }
      const res = await getArticleList(params)
      if (res && res.code === 200 && res.data) {
        this.articleList = res.data.list || []
        this.total = res.data.total || 0
      }
    },
    async loadDraftList() {
      const params = {
        page: this.currentPage,
        size: this.pageSize,
        title: this.searchText || ''
      }
      const res = await getDraftList(params)
      if (res && res.code === 200 && res.data) {
        this.draftList = res.data.list || []
        this.draftCount = res.data.total || 0
        this.total = res.data.total || 0
      }
    },
    changeStatus(status) {
      this.activeStatus = status
      this.currentPage = 1
      this.loadArticleList()
    },
    handleSearch() {
      this.currentPage = 1
      this.loadData()
    },
    handlePageChange(page) {
      this.currentPage = page
      this.loadData()
    },
    goToPublish() {
      window.open('/creator/publish', '_blank')
    },
    operateArticle(id, type) {
      switch (type) {
        case 'edit':
          window.open(`/creator/publish?id=${id}`, '_blank')
          break
        case 'del':
          this.$confirm('确定要删除这篇文章吗？', '提示', { type: 'warning' }).then(() => {
            this.doDeleteArticle(id)
          })
          break
      }
    },
    operateDraft(id, type) {
      switch (type) {
        case 'edit':
          window.open(`/creator/publish?id=${id}&type=draft`, '_blank')
          break
        case 'del':
          this.$confirm('确定要删除这个草稿吗？', '提示', { type: 'warning' }).then(() => {
            this.doDeleteDraft(id)
          })
          break
      }
    },
    async doDeleteArticle(id) {
      const res = await deleteArticle(id)
      if (res && res.code === 200) {
        this.$message.success('删除成功')
        this.loadData()
      } else {
        this.$message.error(res?.errorMessage || '删除失败')
      }
    },
    async doDeleteDraft(id) {
      const res = await deleteDraft(id)
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

.content-manage {
  min-height: calc(100vh - 50px);
  background-color: @bgGray;
  padding: 20px;

  .header-bar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    padding: 16px 24px;
    background: #fff;
    border: 1px solid @borderLight;
    border-radius: @creatorRadius;
    box-shadow: @creatorShadow;

    .tabs {
      display: flex;
      font-size: 16px;
      gap: 8px;
      span {
        cursor: pointer;
        padding: 8px 18px;
        color: @textSecondary;
        border-radius: @creatorRadiusSm;
        font-weight: 500;
        transition: all 0.2s;

        &:hover {
          color: @brandBlue;
          background: @menuActiveBg;
        }

        &.active {
          color: @brandBlue;
          background: @menuActiveBg;
          font-weight: 600;
        }
      }
    }

    .search-box {
      width: 280px;

      .el-input__inner {
        border-radius: @creatorRadiusSm;
      }
    }
  }

  .status-tabs {
    margin-bottom: 16px;
    display: flex;
    flex-wrap: wrap;
    .el-tag {
      cursor: pointer;
      margin-right: 8px;
      padding: 5px 16px;
      border-radius: @creatorRadiusSm;
      background: #fff;
      border: 1px solid @borderLight;
      color: @textSecondary;
      transition: all 0.2s;

      &:hover {
        border-color: @brandBlue;
        color: @brandBlue;
      }

      &.active-tab {
        background-color: @brandBlue;
        border-color: @brandBlue;
        color: #fff;
        font-weight: 500;
      }
    }
  }

  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 72px 0;
    background: #fff;
    border: 1px solid @borderLight;
    border-radius: @creatorRadius;
    box-shadow: @creatorShadow;

    .empty-icon {
      font-size: 60px;
      margin-bottom: 20px;
      width: 104px;
      height: 104px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 50%;
      background: linear-gradient(135deg, #F1F5FE 0%, #E8F3FF 100%);
    }

    .empty-text {
      font-size: 15px;
      color: @colorStatLabel;
      margin-bottom: 24px;
    }

    .el-button {
      border-radius: @creatorRadiusSm;
      padding: 11px 28px;
      font-weight: 500;
    }
  }

  .article-list {
    background-color: #fff;
    border: 1px solid @borderLight;
    border-radius: @creatorRadius;
    box-shadow: @creatorShadow;
    padding: 6px 24px;

    .article-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 18px 0;
      border-bottom: 1px solid #f0f1f5;
      transition: transform 0.15s ease;

      &:hover {
        .article-title {
          color: @brandBlue;
        }
      }

      &:last-child {
        border-bottom: none;
      }

      .article-content {
        flex: 1;
        min-width: 0;

        .article-title {
          font-size: 16px;
          color: @textPrimary;
          font-weight: 500;
          margin-bottom: 8px;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          transition: color 0.2s;
          cursor: pointer;
        }

        .article-meta {
          display: flex;
          align-items: center;
          font-size: 12px;
          color: @colorStatLabel;

          .status-tag {
            margin-left: 12px;
            padding: 2px 10px;
            border-radius: 20px;
            font-size: 11px;
            font-weight: 500;
            display: inline-flex;
            align-items: center;

            &::before {
              content: '';
              width: 6px;
              height: 6px;
              border-radius: 50%;
              margin-right: 5px;
              background: currentColor;
            }
          }

          .status-draft { background: @statusDraftBg; color: @statusDraftFg; }
          .status-reviewing { background: @statusReviewBg; color: @statusReviewFg; }
          .status-rejected { background: @statusRejectBg; color: @statusRejectFg; }
          .status-published { background: @statusPublishBg; color: @statusPublishFg; }
        }
      }

      .article-actions {
        display: flex;
        align-items: center;
        padding: 0 8px;
        .el-dropdown-link {
          cursor: pointer;
          color: @colorStatLabel;
          width: 30px;
          height: 30px;
          border-radius: 50%;
          display: inline-flex;
          align-items: center;
          justify-content: center;
          transition: all 0.2s;

          &:hover {
            color: @brandBlue;
            background: @menuActiveBg;
          }
        }
      }
    }
  }

  .pagination {
    text-align: center;
    margin-top: 24px;
    background: #fff;
    border: 1px solid @borderLight;
    border-radius: @creatorRadius;
    padding: 16px 0;
    box-shadow: @creatorShadow;
  }
}
</style>
