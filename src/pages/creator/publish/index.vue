<template>
  <div class="publish-editor-page">
    <div class="editor-topbar">
      <div class="topbar-left">
        <input
          v-model="FormData.title"
          type="text"
          class="title-input"
          placeholder="输入文章标题..."
        />
      </div>
      <div class="topbar-right">
        <span class="draft-save-status" :class="saveStatus">
          <template v-if="saveStatus === 'saving'">保存中...</template>
          <template v-else-if="saveStatus === 'saved'">保存成功</template>
          <template v-else-if="saveStatus === 'error'">出现异常</template>
        </span>
        <el-button size="medium" @click="goDraftBox">草稿箱</el-button>
        <el-button size="medium" type="primary" :disabled="saveStatus !== 'saved'" @click="openPublishDrawer">发布</el-button>
        <div class="user-avatar">
          <img v-if="userAvatar" :src="userAvatar" class="avatar-img" />
          <span v-else class="avatar-placeholder">&#xf007;</span>
        </div>
      </div>
    </div>

    <div class="editor-content-area">
      <ByteMdEditor
        ref="byteMdEditor"
        v-model="FormData.content"
        class="main-editor"
        :sync-scroll="syncScroll"
        @change="handleContentChange"
        @import-doc="openImportDialog"
      />
    </div>

    <div class="editor-statusbar">
      <div class="status-left">
        <span class="status-item">字符数 {{ charCount }}</span>
        <span class="status-divider">|</span>
        <span class="status-item">行数 {{ lineCount }}</span>
        <span class="status-divider">|</span>
        <span class="status-item">正文字数 {{ wordCount }}</span>
      </div>
      <div class="status-right">
        <label class="sync-scroll-label">
          <input type="checkbox" v-model="syncScroll" />
          <span>同步滚动</span>
        </label>
        <a class="back-to-top" @click="backToTop">回到顶部</a>
      </div>
    </div>

    <el-drawer
      :visible.sync="publishDrawerVisible"
      direction="rtl"
      size="420px"
      :with-header="false"
      :show-close="false"
      class="publish-drawer publish-drawer-custom"
      :wrapper-closable="true"
      :modal="true"
      :modal-append-to-body="true"
      :append-to-body="true"
      :close-on-press-escape="true"
    >
      <div class="drawer-inner">
        <div class="drawer-header">
          <h3>发布文章</h3>
          <i class="fa fa-times close-drawer" @click="publishDrawerVisible = false"></i>
        </div>
        <div class="drawer-body">
          <div class="drawer-section">
            <label class="section-label">
              分类 <span class="required">*</span>
            </label>
            <el-radio-group v-model="FormData.channel_id" class="channel-chips">
              <el-radio-button
                v-for="item in channel_list"
                :key="item.id"
                :label="item.id"
                border
              >{{ item.name }}</el-radio-button>
            </el-radio-group>
          </div>

          <div class="drawer-section">
            <label class="section-label">文章封面</label>
            <div class="cover-upload-wrapper">
              <div v-if="FormData.cover_image" class="cover-preview">
                <img :src="coverPreview" class="cover-img" @click="previewCover" />
                <i class="el-icon-close cover-remove" @click="removeCover"></i>
              </div>
              <el-upload
                v-else
                :show-file-list="false"
                :before-upload="beforeCoverUpload"
                :http-request="handleCoverUpload"
                accept="image/*"
                class="cover-uploader"
              >
                <div class="cover-upload-btn">
                  <i class="el-icon-plus"></i>
                  <span>上传封面</span>
                </div>
              </el-upload>
              <p class="cover-tip">建议尺寸：192*128px (封面仅展示在首页信息流中)</p>
            </div>
          </div>

          <div class="drawer-section">
            <label class="section-label">添加标签 <span class="required">*</span> <span class="tag-limit">最多{{ maxTags }}个</span></label>
            <el-select
              v-model="selectedTags"
              multiple
              filterable
              remote
              reserve-keyword
              placeholder="搜索并选择标签"
              :remote-method="searchTags"
              :loading="tagLoading"
              size="small"
              class="tag-select"
              @visible-change="onTagDropdownVisible"
            >
              <el-option
                v-for="item in tagOptions"
                :key="item.id"
                :label="item.name"
                :value="item.name"
                :disabled="selectedTags.length >= maxTags && selectedTags.indexOf(item.name) === -1"
              >
                <span style="float: left">{{ item.name }}</span>
                <span style="float: right; color: #8492a6; font-size: 12px">{{ item.category }}</span>
              </el-option>
            </el-select>
            <div class="tag-remaining-tip" v-if="selectedTags.length > 0 || maxTags > 0">
              你还能添加 <span class="remaining-count">{{ remainingTags }}</span> 个标签
            </div>
          </div>

          <div class="drawer-section" v-if="canSchedulePublish">
            <label class="section-label">定时发布</label>
            <el-date-picker
              v-model="FormData.publish_time"
              type="datetime"
              placeholder="选择发布时间"
              size="small"
              class="datetime-picker"
            />
          </div>

          <div class="drawer-section">
            <label class="section-label">话题</label>
            <el-select
              v-model="FormData.topic"
              filterable
              remote
              reserve-keyword
              clearable
              placeholder="搜索并选择话题"
              :remote-method="searchTopics"
              :loading="topicLoading"
              size="small"
              class="topic-select"
              @visible-change="onTopicDropdownVisible"
            >
              <el-option
                v-for="item in topicOptions"
                :key="item.id"
                :label="item.name"
                :value="item.name"
              >
                <span style="float: left">{{ item.name }}</span>
                <span style="float: right; color: #8492a6; font-size: 12px">{{ item.description }}</span>
              </el-option>
            </el-select>
          </div>

          <div class="drawer-section">
            <label class="section-label">收录至专栏</label>
            <el-select
              v-model="FormData.column_id"
              clearable
              filterable
              placeholder="请搜索添加专栏"
              size="small"
              class="column-select"
              @visible-change="onColumnDropdownVisible"
            >
              <el-option
                v-for="item in columnOptions"
                :key="item.id"
                :label="item.name"
                :value="item.id"
              ></el-option>
            </el-select>
          </div>

          <div class="drawer-section">
            <label class="section-label">编辑摘要 <span class="required">*</span></label>
            <div class="summary-wrapper">
              <el-input
                v-model="FormData.summary"
                type="textarea"
                :rows="4"
                placeholder="自动从文章中提取摘要，也可以手动编辑"
                maxlength="100"
                class="summary-textarea"
              />
              <span class="summary-count">{{ (FormData.summary || '').length }}/100</span>
            </div>
          </div>
        </div>
        <div class="drawer-footer">
          <el-button @click="publishDrawerVisible = false">取消</el-button>
          <el-button type="primary" :disabled="saveStatus !== 'saved'" @click="confirmPublish">确定并发布</el-button>
        </div>
      </div>
    </el-drawer>

    <el-dialog
      title="文章导入"
      :visible.sync="importDialogVisible"
      width="480px"
      :close-on-click-modal="false"
      :modal-append-to-body="true"
      :append-to-body="true"
      class="import-dialog"
    >
      <el-upload
        ref="importUpload"
        drag
        action=""
        accept=".md"
        :limit="1"
        :file-list="importFileList"
        :auto-upload="false"
        :on-change="handleImportFileChange"
        :on-remove="handleImportFileRemove"
        :before-upload="beforeImportUpload"
        class="import-uploader"
      >
        <i class="el-icon-upload"></i>
        <div class="el-upload__text">拖拽 MD 文件到这里，或点击进行上传</div>
        <div class="el-upload__tip" slot="tip">
          仅支持导入 MD 格式的文档，最大 10 MB，每次仅可上传 1 篇<br/>
          请在上传前检查文档图片路径，本地路径的图片会上传失败
        </div>
      </el-upload>
      <span slot="footer" class="dialog-footer">
        <el-button @click="importDialogVisible = false">取 消</el-button>
        <el-button type="primary" :loading="importLoading" @click="confirmImport">导入文档</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
  import ByteMdEditor from "@/pages/creator/components/editor/ByteMdEditor.vue";
  import { getArticleById, getDraftById, getColumnList } from "@/apis/creator/content";
  import {
    getChannels,
    getTagList,
    getTopicList,
    importMarkdown
  } from "@/apis/creator/publish";
  import { mapGetters } from 'vuex';
  import { permission } from "@/utils/permission";
  import { API_DRAFT_CREATE, API_DRAFT_UPDATE, API_DRAFT_PUBLISH } from "@/pages/creator/constants/api";
  import wemediaRequest from '@/common/article_request';

  export default {
    name: "PublishEditor",
    components: { ByteMdEditor },
    data() {
      return {
        FormData: {
          id: "",
          title: "",
          type: "0",
          labels: "",
          topic: "",
          publish_time: "",
          channel_id: null,
          column_id: null,
          content: "",
          summary: "",
          cover_image: ""
        },
        maxTags: 1,
        canAddVideo: false,
        canSchedulePublish: false,
        host: '',
        channel_list: [],
        publishDrawerVisible: false,
        syncScroll: true,
        charCount: 0,
        lineCount: 1,
        wordCount: 0,
        // 标签相关
        selectedTags: [],
        tagOptions: [],
        tagLoading: false,
        // 话题相关
        topicOptions: [],
        topicLoading: false,
        // 专栏相关
        columnList: [],
        columnLoading: false,
        columnOptions: [],
        // 文档导入
        importDialogVisible: false,
        importFileList: [],
        importLoading: false,
        // 草稿自动保存
        draftId: null,
        draftTimer: null,
        draftSaving: false,
        draftContent: '',
        draftHash: '',
        saveStatus: '',
        coverPreview: null
      };
    },
    watch: {
      selectedTags: {
        handler(newVal) {
          this.FormData.labels = newVal.join(',')
          this.saveStatus = ''
          this.scheduleDraftSave()
        },
        deep: true
      },
      'FormData.content': {
        handler() { this.saveStatus = ''; this.scheduleDraftSave() }
      },
      'FormData.title': {
        handler() { this.saveStatus = ''; this.scheduleDraftSave() }
      },
      'FormData.channel_id': {
        handler() { this.saveStatus = ''; this.scheduleDraftSave() }
      },
      'FormData.topic': {
        handler() { this.saveStatus = ''; this.scheduleDraftSave() }
      },
      'FormData.column_id': {
        handler() { this.saveStatus = ''; this.scheduleDraftSave() }
      },
      'FormData.summary': {
        handler() { this.saveStatus = ''; this.scheduleDraftSave() }
      },
      'FormData.publish_time': {
        handler() { this.saveStatus = ''; this.scheduleDraftSave() }
      },
      'FormData.cover_image': {
        handler() { this.saveStatus = ''; this.scheduleDraftSave() }
      }
    },
    computed: {
      ...mapGetters(['userInfo']),
      userAvatar() {
        if (this.userInfo && this.userInfo.avatar) {
          return this.userInfo.avatar
        }
        return ''
      },
      userName() {
        return this.userInfo ? (this.userInfo.nickName || '') : ''
      },
      remainingTags() {
        const remaining = this.maxTags - this.selectedTags.length;
        return remaining > 0 ? remaining : 0;
      }
    },
    beforeMount() {
      const token = localStorage.getItem('ACCESS_TOKEN')
      if (!token) {
        this.$router.replace('/home')
        return
      }
      let { id, type } = this.$route.query;
      if (id) {
        if (type === 'draft') {
          this.getDraft(id);
        } else {
          this.getArticle(id);
        }
      }
      this.getChannels();
      this.initPermissions();
    },
    beforeDestroy() {
      if (this.draftTimer) {
        clearTimeout(this.draftTimer)
        this.draftTimer = null
      }
      // 最后保存一次草稿
      if (this.FormData.content || this.FormData.title) {
        this.autoSaveDraft()
      }
    },
    methods: {
      initPermissions() {
        this.maxTags = permission.getMaxTags();
        this.canAddVideo = permission.canAddVideo();
        this.canSchedulePublish = permission.canSchedulePublish();
      },
      // 打开文档导入弹窗
      openImportDialog() {
        this.importDialogVisible = true
        this.importFileList = []
        this.importLoading = false
      },
      // 导入文件选择变化
      handleImportFileChange(file, fileList) {
        this.importFileList = fileList.slice(-1)
      },
      // 移除导入文件
      handleImportFileRemove() {
        this.importFileList = []
      },
      // 导入前校验
      beforeImportUpload(file) {
        const isMd = file.name.toLowerCase().endsWith('.md')
        const isLt10M = file.size / 1024 / 1024 < 10
        if (!isMd) {
          this.$message.error('仅支持导入 MD 格式的文档')
        }
        if (!isLt10M) {
          this.$message.error('文档大小不能超过 10 MB')
        }
        return isMd && isLt10M
      },
      // 确认导入
      confirmImport() {
        if (this.importFileList.length === 0) {
          this.$message.warning('请先选择要导入的 MD 文档')
          return
        }
        const file = this.importFileList[0].raw
        if (!this.beforeImportUpload(file)) return

        this.importLoading = true
        importMarkdown(file).then(res => {
          this.importLoading = false
          if (res && res.code === 200) {
            const { title, content } = res.data || {}
            if (title) {
              this.FormData.title = title
            }
            if (content) {
              this.FormData.content = content
              this.handleContentChange(content)
            }
            this.importDialogVisible = false
            this.$message.success('文档导入成功')
          } else {
            this.$message.error(res?.message || '文档导入失败')
          }
        }).catch(err => {
          this.importLoading = false
          this.$message.error(err?.message || '文档导入失败')
        })
      },
      // 标签搜索
      searchTags(query) {
        if (query) {
          this.tagLoading = true
          getTagList(query).then(res => {
            this.tagLoading = false
            if (res && res.code === 200) {
              this.tagOptions = res.data || []
            }
          }).catch(() => {
            this.tagLoading = false
          })
        } else {
          this.tagOptions = []
        }
      },
      // 标签下拉展开时加载全部
      onTagDropdownVisible(visible) {
        if (visible) {
          this.tagLoading = true
          getTagList('').then(res => {
            this.tagLoading = false
            if (res && res.code === 200) {
              this.tagOptions = res.data || []
            }
          }).catch(() => {
            this.tagLoading = false
          })
        }
      },
      // 话题搜索
      searchTopics(query) {
        if (query) {
          this.topicLoading = true
          getTopicList(query).then(res => {
            this.topicLoading = false
            if (res && res.code === 200) {
              this.topicOptions = res.data?.list || []
            }
          }).catch(() => {
            this.topicLoading = false
          })
        } else {
          this.topicOptions = []
        }
      },
      // 话题下拉展开时加载全部
      onTopicDropdownVisible(visible) {
        if (visible) {
          this.topicLoading = true
          getTopicList('').then(res => {
            this.topicLoading = false
            if (res && res.code === 200) {
              this.topicOptions = res.data?.list || []
            }
          }).catch(() => {
            this.topicLoading = false
          })
        }
      },
      // 获取专栏列表
      async loadColumnList() {
        this.columnLoading = true
        try {
          const result = await getColumnList()
          if (result && result.code === 200) {
            const list = result.data?.records || result.data?.list || result.data || []
            this.columnList = list
            this.columnOptions = list.map(item => ({
              id: item.id,
              name: item.name
            }))
          }
        } catch (e) {
          // 专栏加载失败不阻塞发布
        } finally {
          this.columnLoading = false
        }
      },
      // 专栏下拉框显示时加载数据
      async onColumnDropdownVisible(visible) {
        if (visible && this.columnOptions.length === 0) {
          await this.loadColumnList()
        }
      },
      // 封面上传前校验
      beforeCoverUpload(file) {
        const isImage = file.type.startsWith('image/')
        const isLt5M = file.size / 1024 / 1024 < 5
        if (!isImage) {
          this.$message.error('只能上传图片文件')
          return false
        }
        if (!isLt5M) {
          this.$message.error('图片大小不能超过 5MB')
          return false
        }
        return true
      },
      // 封面上传处理（使用 OSS 直传）
      async handleCoverUpload({ file }) {
        try {
          const { uploadFile } = await import('@/common/oss_upload')
          const url = await uploadFile(file)
          if (url) {
            this.FormData.cover_image = url
            this.coverPreview = url
            this.saveStatus = ''
            this.scheduleDraftSave()
          }
        } catch (e) {
          this.$message.error('封面上传失败')
        }
      },
      // 移除封面
      removeCover() {
        this.FormData.cover_image = ''
        this.coverPreview = null
      },
      // 预览封面
      previewCover() {
        this.$alert('<img src="' + this.coverPreview + '" style="max-width:100%">', '封面预览', {
          dangerouslyUseHTMLString: true
        })
      },
      async getChannels() {
        let result = await getChannels();
        this.channel_list = result.data;
      },
      // 调度草稿保存（防抖2秒）
      scheduleDraftSave() {
        if (this.draftTimer) clearTimeout(this.draftTimer)
        this.draftTimer = setTimeout(() => {
          this.autoSaveDraft()
        }, 2000)
      },
      // 计算当前草稿哈希（包含所有属性，用于检测变更）
      computeDraftHash() {
        const parts = [
          this.FormData.content || '',
          this.FormData.title || '',
          this.FormData.channel_id || '',
          this.FormData.labels || '',
          this.FormData.topic || '',
          this.FormData.summary || '',
          this.FormData.publish_time || '',
          this.FormData.cover_image || ''
        ]
        return parts.join('|||')
      },
      async autoSaveDraft() {
        if (!this.FormData.content && !this.FormData.title) return
        const currentHash = this.computeDraftHash()
        if (currentHash === this.draftHash) return
        if (this.draftSaving) return

        this.draftSaving = true
        this.saveStatus = 'saving'
        this.draftHash = currentHash
        try {
          let data = {
            id: this.draftId,
            title: this.FormData.title,
            content: this.FormData.content,
            channelId: this.FormData.channel_id,
            columnId: this.FormData.column_id || null,
            images: '',
            labels: this.FormData.labels,
            // 后端 ApArticleDraft.tags 为 JSON 数组(List)，需由逗号分隔的 labels 转换
            tags: this.FormData.labels ? this.FormData.labels.split(',').map(s => s.trim()).filter(s => s.length > 0) : [],
            topic: this.FormData.topic,
            summary: this.FormData.summary,
            publishTime: this.FormData.publish_time,
            layout: 0,
            coverImage: this.FormData.cover_image || ''
          }

          let result
          if (!this.draftId) {
            // 首次创建草稿
            result = await wemediaRequest.post(API_DRAFT_CREATE, data)
          } else {
            // 更新已有草稿
            result = await wemediaRequest.put(API_DRAFT_UPDATE, data)
          }
          if (result && result.code === 200) {
            if (result.data && result.data.id) {
              this.draftId = result.data.id
            }
            this.saveStatus = 'saved'
          } else {
            this.saveStatus = 'error'
          }
        } catch (e) {
          this.saveStatus = 'error'
        }
        this.draftSaving = false
      },
      async getArticle(id) {
        let result = await getArticleById(id);
        this.FormData = {
          id: result.data.id,
          title: result.data.title,
          channel_id: result.data.channel_id,
          column_id: result.data.columnId || null,
          labels: result.data.labels,
          topic: result.data.topic || "",
          type: "" + result.data.type,
          publish_time: result.data.publish_time,
          content: result.data.content || "",
          summary: result.data.summary || this.generateSummary(result.data.content || ""),
          cover_image: result.data.cover_image || ""
        }
        this.coverPreview = result.data.cover_image || null
        this.selectedTags = (result.data.labels || "").split(",").map(item => item.trim()).filter(item => item.length > 0);
        this.host = result.host
        this.transImages(this.FormData.type, result.data.images);
        this.updateCounts(result.data.content || "");
      },
      async getDraft(id) {
        try {
          let result = await getDraftById(id);
          this.draftId = result.data.id
          this.FormData = {
            id: result.data.id,
            title: result.data.title || '',
            channel_id: result.data.channelId || result.data.channel_id || null,
            column_id: result.data.columnId || null,
            // 草稿详情接口返回 tags(数组)，同时兼容 labels(逗号分隔字符串)；标签缺失会回退为空串，避免误清空已有标签
            labels: result.data.labels || (Array.isArray(result.data.tags) ? result.data.tags.join(',') : ''),
            topic: result.data.topic || "",
            type: "0",
            publish_time: result.data.publishTime || result.data.publish_time || '',
            content: result.data.content || "",
            summary: result.data.summary || this.generateSummary(result.data.content || ""),
            cover_image: result.data.coverImage || result.data.cover_image || ""
          }
          this.coverPreview = result.data.coverImage || result.data.cover_image || null
          const draftLabelStr = result.data.labels || (Array.isArray(result.data.tags) ? result.data.tags.join(',') : '')
          this.selectedTags = draftLabelStr.split(",").map(item => item.trim()).filter(item => item.length > 0);
          this.host = result.host || ''
          this.transImages("0", result.data.images || result.data.image);
          this.updateCounts(result.data.content || "");
        } catch (e) {
          this.$message.error('草稿加载失败')
        }
      },
      generateSummary(content) {
        if (!content) return "";
        let text = content.replace(/[#*`>\-\[\]()!_~\n\r]/g, '').trim();
        return text.substring(0, 100);
      },
      handleContentChange(val) {
        this.updateCounts(val);
        if (!this.FormData.summary || this.FormData.summary === this.generateSummary(this._lastContent || "")) {
          this.FormData.summary = this.generateSummary(val);
        }
        this._lastContent = val;
      },
      updateCounts(content) {
        this.charCount = content.length;
        this.lineCount = content.split('\n').length;
        const plainText = content.replace(/[#*`>\-\[\]()!_~\n\r\s]/g, '');
        this.wordCount = plainText.length;
      },
      transImages(type, images) {
      },
      getImages() {
        return [];
      },
      saveDraft() {
        this.autoSaveDraft()
      },
      goDraftBox() {
        this.$router.push({ path: '/creator/article/list' })
      },
      openPublishDrawer() {
        if (!this.FormData.summary) {
          this.FormData.summary = this.generateSummary(this.FormData.content);
        }
        this.loadColumnList();
        this.publishDrawerVisible = true;
      },
      backToTop() {
        const editorEl = this.$el.querySelector('.main-editor');
        if (editorEl) {
          const bytemdEl = editorEl.querySelector('.bytemd');
          if (bytemdEl) {
            const editorPanels = bytemdEl.querySelectorAll('.bytemd-editor, .bytemd-preview');
            editorPanels.forEach(panel => {
              panel.scrollTop = 0;
            });
          }
        }
      },
      roundUpToFiveMinutes(date) {
        const d = new Date(date);
        const minutes = d.getMinutes();
        const remainder = minutes % 5;
        if (remainder !== 0) {
          d.setMinutes(minutes + (5 - remainder));
          d.setSeconds(0);
        } else {
          d.setSeconds(0);
        }
        return d.toISOString();
      },
      confirmPublish() {
        this.publish();
      },
      async publish() {
        this.FormData.labels = this.selectedTags.join(',');

        // 校验
        if (!this.FormData.title || this.FormData.title.length < 5 || this.FormData.title.length > 64) {
          this.$message({ type: "warning", message: "文章标题不能小于5个字符或大于32个字符" });
          return;
        }
        if (!this.FormData.channel_id) {
          this.$message({ type: "warning", message: "请选择文章分类" });
          return;
        }
        if (!this.selectedTags || this.selectedTags.length === 0) {
          this.$message({ type: "warning", message: "请添加至少一个标签" });
          return;
        }
        if (this.selectedTags.length > this.maxTags) {
          this.$message({ type: "warning", message: `最多只能添加${this.maxTags}个标签` });
          return;
        }
        if (!this.FormData.summary || this.FormData.summary.trim().length === 0) {
          this.$message({ type: "warning", message: "请填写文章摘要" });
          return;
        }
        if (!this.FormData.labels || this.FormData.labels.length > 20) {
          this.$message({ type: "warning", message: "内容标签不能为空或超过20字符" });
          return;
        }
        if (!this.FormData.content) {
          this.$message({ type: "warning", message: "文章内容不能为空" });
          return;
        }
        if (!this.draftId) {
          this.$message({ type: "warning", message: "草稿尚未保存成功，请稍后再试" });
          return;
        }

        // 取消防抖，立即强制保存最新属性到草稿
        if (this.draftTimer) {
          clearTimeout(this.draftTimer)
          this.draftTimer = null
        }
        // 强制标记变更（绕过 draftHash 比较），确保本次一定发起 /update 请求
        this.draftHash = ''
        await this.autoSaveDraft();
        if (this.saveStatus !== 'saved') {
          this.$message({ type: "warning", message: "草稿保存失败，请重试" });
          return;
        }

        try {
          // 发布只发送 draft_id
          let result = await wemediaRequest.post(API_DRAFT_PUBLISH, { draftId: this.draftId });
          if (result && result.code === 200) {
            this.$message({ type: "success", message: "文章发布成功，已进入审核" });
            this.publishDrawerVisible = false;
            this.$router.replace({ path: "/creator/article/list" });
          } else {
            this.$message({ type: "error", message: result?.errorMessage || "发布失败" });
          }
        } catch (e) {
          this.$message({ type: "error", message: "发布失败，请重试" });
        }
      }
    }
  };
</script>

<style rel="stylesheet/less" lang="less" scoped>
  @import "../layout/styles/variables.less";

  .publish-editor-page {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: #ffffff;
    z-index: 2000;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .editor-topbar {
    height: 60px;
    background: #ffffff;
    border-bottom: 1px solid #e4e6eb;
    display: flex;
    align-items: center;
    padding: 0 24px;
    flex-shrink: 0;
  }

  .topbar-left {
    flex: 1;
    .title-input {
      width: 100%;
      height: 40px;
      border: none;
      outline: none;
      font-size: 24px;
      font-weight: 600;
      color: @textPrimary;
      background: transparent;
      &::placeholder {
        color: #c0c4cc;
        font-weight: 400;
      }
    }
  }

  .topbar-right {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-shrink: 0;
    .draft-save-status {
      font-size: 13px;
      margin-right: 4px;
      &.saving {
        color: #e6a23c;
      }
      &.saved {
        color: #67c23a;
      }
      &.error {
        color: #f56c6c;
      }
    }
    .user-avatar {
      width: 32px;
      height: 32px;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      margin-left: 8px;
      .avatar-img {
        width: 32px;
        height: 32px;
        border-radius: 50%;
        object-fit: cover;
      }
      .avatar-placeholder {
        font-family: fontawesome;
        font-size: 24px;
        color: #8a919f;
      }
    }
  }

  .editor-content-area {
    flex: 1;
    overflow: auto;
    display: flex;
    .main-editor {
      flex: 1;
      width: 100%;
      min-height: 100%;
    }
  }

  .editor-statusbar {
    height: 30px;
    background: #f7f8fa;
    border-top: 1px solid #e4e6eb;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 24px;
    font-size: 12px;
    color: @textSecondary;
    flex-shrink: 0;
  }

  .status-left {
    display: flex;
    align-items: center;
    .status-item {
      padding: 0 8px;
    }
    .status-divider {
      color: #dcdfe6;
    }
  }

  .status-right {
    display: flex;
    align-items: center;
    gap: 16px;
    .sync-scroll-label {
      display: flex;
      align-items: center;
      gap: 4px;
      cursor: pointer;
      user-select: none;
      input {
        cursor: pointer;
      }
    }
    .back-to-top {
      color: @brandBlue;
      cursor: pointer;
      &:hover {
        text-decoration: underline;
      }
    }
  }

  .publish-drawer {
    :deep(.el-drawer) {
      box-shadow: -4px 0 20px rgba(0,0,0,0.08);
    }
    :deep(.el-drawer__body) {
      padding: 0;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
  }

  .publish-drawer-custom {
    :deep(.el-drawer__wrapper) {
      background-color: rgba(0, 0, 0, 0.2);
    }
    :deep(.el-drawer__container) {
      background-color: transparent;
    }
    :deep(.v-modal) {
      background-color: rgba(0, 0, 0, 0.2);
    }
  }

  .drawer-inner {
    display: flex;
    flex-direction: column;
    height: 100%;
  }

  .drawer-header {
    height: 56px;
    padding: 0 24px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-bottom: 1px solid #e4e6eb;
    flex-shrink: 0;
    h3 {
      margin: 0;
      font-size: 16px;
      font-weight: 600;
      color: @textPrimary;
    }
    .close-drawer {
      font-size: 18px;
      color: @textMuted;
      cursor: pointer;
      padding: 8px;
      &:hover {
        color: @textPrimary;
      }
    }
  }

  .drawer-body {
    flex: 1;
    overflow-y: auto;
    padding: 24px;
  }

  .drawer-section {
    margin-bottom: 28px;
    .section-label {
      display: block;
      font-size: 14px;
      font-weight: 600;
      color: @textPrimary;
      margin-bottom: 12px;
      .required {
        color: @red;
        margin-left: 2px;
      }
      .tag-limit {
        font-weight: 400;
        color: @textMuted;
        font-size: 12px;
        margin-left: 8px;
      }
    }
  }

  .cover-upload-wrapper {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .cover-uploader {
    display: inline-block;
  }

  .cover-upload-btn {
    width: 120px;
    height: 80px;
    border: 1px dashed #d9d9d9;
    border-radius: 4px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    color: #8c8c8c;
    font-size: 12px;
    transition: border-color 0.3s;

    &:hover {
      border-color: #409eff;
      color: #409eff;
    }

    .el-icon-plus {
      font-size: 24px;
      margin-bottom: 4px;
    }
  }

  .cover-preview {
    position: relative;
    display: inline-block;

    .cover-img {
      width: 120px;
      height: 80px;
      object-fit: cover;
      border-radius: 4px;
      cursor: pointer;
    }

    .cover-remove {
      position: absolute;
      top: -6px;
      right: -6px;
      width: 20px;
      height: 20px;
      background: #ff4d4f;
      color: #fff;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 12px;
      cursor: pointer;
    }
  }

  .cover-tip {
    font-size: 12px;
    color: #999;
    margin: 0;
  }

  .datetime-picker {
    width: 100%;
  }

  .channel-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    :deep(.el-radio-button__inner) {
      border-radius: 4px;
      border: 1px solid #e4e6eb;
      background: #fff;
      color: @textSecondary;
      padding: 8px 16px;
      font-size: 13px;
      box-shadow: none;
      &:hover {
        color: @brandBlue;
        border-color: @brandBlue;
      }
    }
    :deep(.el-radio-button__orig-radio:checked + .el-radio-button__inner) {
      background: @brandBlue;
      border-color: @brandBlue;
      color: #fff;
      box-shadow: none;
    }
    :deep(.el-radio-button:first-child .el-radio-button__inner),
    :deep(.el-radio-button:last-child .el-radio-button__inner) {
      border-radius: 4px;
    }
    :deep(.el-radio-button__inner) {
      border-left: 1px solid #e4e6eb;
    }
  }

  .tag-select, .topic-select {
    width: 100%;
  }

  .tag-remaining-tip {
    font-size: 12px;
    color: #8492a6;
    margin-top: 4px;
  }

  .remaining-count {
    color: #409eff;
    font-weight: 600;
  }

  .column-select {
    width: 100%;
  }

  .summary-wrapper {
    position: relative;
    .summary-textarea {
      :deep(.el-textarea__inner) {
        border-radius: 4px;
        padding-bottom: 28px;
        font-size: 13px;
        line-height: 1.6;
      }
    }
    .summary-count {
      position: absolute;
      right: 12px;
      bottom: 8px;
      font-size: 12px;
      color: @textMuted;
    }
  }

  .drawer-footer {
    padding: 16px 24px;
    border-top: 1px solid #e4e6eb;
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    flex-shrink: 0;
  }

  .import-dialog {
    :deep(.el-dialog__body) {
      padding: 20px 24px 10px;
    }
    .import-uploader {
      width: 100%;
      :deep(.el-upload) {
        width: 100%;
      }
      :deep(.el-upload-dragger) {
        width: 100%;
        height: 240px;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        background-color: #f7f8fa;
        border: 1px dashed #d9d9d9;
        border-radius: 8px;
        .el-icon-upload {
          font-size: 48px;
          color: #1e80ff;
          margin: 0 0 16px;
          line-height: 1;
        }
        .el-upload__text {
          font-size: 15px;
          color: #515767;
          margin-bottom: 12px;
        }
      }
      :deep(.el-upload__tip) {
        text-align: center;
        font-size: 13px;
        color: #8a93a6;
        line-height: 1.8;
        margin-top: 16px;
      }
    }
  }
</style>
