<template>
  <div class="column-manage">
    <div class="header-bar">
      <div class="page-title">专栏</div>
      <div class="header-right">
        <el-input
          v-model="searchText"
          placeholder="请输入标题关键字"
          prefix-icon="el-icon-search"
          clearable
          @keyup.enter.native="handleSearch"
          style="width: 280px; margin-right: 12px;"
        />
        <el-button type="primary" @click="openCreateModal">新建专栏</el-button>
      </div>
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

    <div v-if="courseList.length === 0" class="empty-state">
      <div class="empty-icon">📚</div>
      <div class="empty-text">这里什么都没有</div>
      <el-button type="primary" @click="openCreateModal">新建专栏</el-button>
    </div>

    <div v-else class="column-list">
      <div v-for="course in courseList" :key="course.id" class="column-item">
        <div class="column-cover">
          <img :src="course.coverImage || defaultCover" alt="cover" />
        </div>
        <div class="column-content">
          <div class="column-title">{{ course.title }}</div>
          <div class="column-desc">{{ course.description }}</div>
          <div class="column-footer">
            <span class="column-stat">文章数 {{ course.articleCount || 0 }}</span>
            <span class="column-stat">订阅人数 {{ course.subscribeCount || 0 }}</span>
            <span class="status-tag" :class="getStatusClass(course.status)">{{ getStatusLabel(course.status) }}</span>
          </div>
        </div>
        <div class="column-actions">
          <el-dropdown trigger="click" @command="(cmd) => operateBtn(course.id, cmd)">
            <span class="el-dropdown-link"><i class="el-icon-more"></i></span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="modify">修改介绍</el-dropdown-item>
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

    <el-dialog title="新建专栏" :visible.sync="showCreateModal" width="500px">
      <el-form :model="columnForm" label-width="80px">
        <el-form-item label="专栏名称" required>
          <el-input v-model="columnForm.title" placeholder="请输入专栏名称" />
        </el-form-item>
        <el-form-item label="专栏简介" required>
          <el-input v-model="columnForm.description" type="textarea" :rows="4" placeholder="请输入专栏简介" />
        </el-form-item>
        <el-form-item label="封面图片">
          <div class="cover-upload-area" @click="$refs.coverFileInput.click()">
            <div v-if="!columnForm.coverImage" class="upload-placeholder">
              <i class="el-icon-plus upload-icon"></i>
              <span class="upload-text">点击上传封面</span>
            </div>
            <img v-else :src="columnForm.coverImage" class="cover-preview-img" />
          </div>
          <input
            ref="coverFileInput"
            type="file"
            accept="image/*"
            style="display:none"
            @change="handleCoverFileChange"
          />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="showCreateModal = false">取消</el-button>
        <el-button type="primary" @click="submitColumn">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
const avatar = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'
import { getColumnList, getColumnStatistics, createColumn, deleteColumn } from '@/apis/creator/content'
import { uploadFile } from '@/common/oss_upload'

export default {
  name: 'ColumnManage',
  data() {
    return {
      courseList: [],
      total: 0,
      defaultCover: avatar,
      currentPage: 1,
      pageSize: 10,
      activeStatus: 'all',
      searchText: '',
      showCreateModal: false,
      columnForm: {
        title: '',
        description: '',
        coverImage: ''
      },
      statusTabs: [
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
  methods: {
    async loadData() {
      await this.loadStatistics()
      await this.loadList()
    },
    async loadStatistics() {
      const res = await getColumnStatistics()
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
        status: this.activeStatus === 'all' ? '' : this.activeStatus,
        title: this.searchText || ''
      }
      const res = await getColumnList(params)
      if (res && res.code === 200 && res.data) {
        this.courseList = res.data.list || []
        this.total = res.data.total || 0
      }
    },
    changeStatus(status) {
      this.activeStatus = status
      this.currentPage = 1
      this.loadList()
    },
    handleSearch() {
      this.currentPage = 1
      this.loadList()
    },
    pageChange(newPage) {
      this.currentPage = newPage
      this.loadList()
    },
    openCreateModal() {
      this.columnForm = { title: '', description: '', coverImage: '' }
      this.showCreateModal = true
    },
    async handleCoverFileChange(e) {
      const file = e.target.files[0]
      if (!file) return
      try {
        const url = await uploadFile(file)
        this.columnForm.coverImage = url
        this.$message.success('封面上传成功')
      } catch (err) {
        this.$message.error('封面上传失败: ' + (err.message || '网络错误'))
      }
      e.target.value = ''
    },
    async submitColumn() {
      if (!this.columnForm.title) {
        this.$message.error('请输入专栏名称')
        return
      }
      if (!this.columnForm.description) {
        this.$message.error('请输入专栏简介')
        return
      }
      const res = await createColumn(this.columnForm)
      if (res && res.code === 200) {
        this.$message.success('创建成功')
        this.showCreateModal = false
        this.loadData()
      } else {
        this.$message.error(res?.errorMessage || '创建失败')
      }
    },
    operateBtn(id, type) {
      switch (type) {
        case 'modify':
          this.$message({ type: 'info', message: '修改介绍功能开发中' })
          break
        case 'del':
          this.$confirm('确定要删除该专栏吗？', '提示', { type: 'warning' }).then(() => {
            this.doDelete(id)
          })
          break
      }
    },
    async doDelete(id) {
      const res = await deleteColumn(id)
      if (res && res.code === 200) {
        this.$message.success('删除成功')
        this.loadData()
      } else {
        this.$message.error(res?.errorMessage || '删除失败')
      }
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

.column-manage {
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

  .column-list {
    background-color: #fff;
    border-radius: 8px;
    padding: 20px;

    .column-item {
      display: flex;
      align-items: flex-start;
      padding: 16px 0;
      border-bottom: 1px solid #f2f3f5;
      &:last-child { border-bottom: none; }

      .column-cover {
        width: 160px;
        height: 90px;
        border-radius: 6px;
        overflow: hidden;
        margin-right: 20px;
        img { width: 100%; height: 100%; object-fit: cover; }
      }

      .column-content {
        flex: 1;

        .column-title {
          font-size: 16px;
          font-weight: 500;
          color: @textPrimary;
          margin-bottom: 8px;
        }

        .column-desc {
          font-size: 14px;
          color: #606266;
          line-height: 1.5;
          margin-bottom: 12px;
          overflow: hidden;
          text-overflow: ellipsis;
          display: -webkit-box;
          -webkit-line-clamp: 2;
          -webkit-box-orient: vertical;
        }

        .column-footer {
          display: flex;
          align-items: center;

          .column-stat {
            font-size: 12px;
            color: #909399;
            margin-right: 24px;
          }

          .status-tag {
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

      .column-actions {
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

  .cover-upload-area {
    width: 200px;
    height: 140px;
    border: 1px dashed #d9d9d9;
    border-radius: 6px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    overflow: hidden;
    transition: all 0.2s;
    background-color: #fafafa;
    &:hover {
      border-color: #1e80ff;
      background-color: #f5f7ff;
    }
    .upload-placeholder {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      .upload-icon {
        font-size: 32px;
        color: #c0c4cc;
        margin-bottom: 10px;
      }
      .upload-text {
        font-size: 14px;
        color: #909399;
        font-weight: 500;
      }
    }
    .cover-preview-img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }
}
</style>
