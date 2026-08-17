<template>
  <div class="byte-md-editor-wrapper">
    <button
      type="button"
      class="import-doc-btn"
      title="导入 Markdown 文档"
      @click="$emit('import-doc')"
    >
      <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm4 18H6V4h7v5h5v11z"/>
        <path d="M8 12h8v2H8zM8 16h5v2H8z"/>
      </svg>
    </button>
    <Editor
      :value="value"
      :plugins="plugins"
      :placeholder="placeholder"
      :locale="locale"
      :uploadImages="uploadImages"
      :editorConfig="editorConfig"
      @change="handleChange"
    />
  </div>
</template>

<script>
import { Editor } from "@bytemd/vue";
import gfm from "@bytemd/plugin-gfm";
import highlight from "@bytemd/plugin-highlight";
import breaks from "@bytemd/plugin-breaks";
import zhHans from "bytemd/locales/zh_Hans.json";
import "bytemd/dist/index.css";
import "github-markdown-css/github-markdown.css";
import "highlight.js/styles/github.css";
import { uploadFile } from '@/common/oss_upload'

export default {
  name: "ByteMdEditor",
  components: { Editor },
  props: {
    value: {
      type: String,
      default: ""
    },
    placeholder: {
      type: String,
      default: "请输入 Markdown 正文..."
    },
    syncScroll: {
      type: Boolean,
      default: true
    },
    // 只读：审核中 / 已发布（默认锁定）的小节禁止编辑
    readonly: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      plugins: [gfm(), highlight(), breaks()],
      locale: zhHans,
      uploadImages: null
    };
  },
  computed: {
    editorConfig() {
      return { readOnly: this.readonly };
    }
  },
  created() {
    this.uploadImages = this.handleUploadImages.bind(this);
  },
  mounted() {
    this.applySyncScroll();
    this.initScrollIndicator();
    this.applyReadOnly();
  },
  beforeDestroy() {
    // 断开滚动指示器的 MutationObserver，避免组件销毁后观察器悬挂
    if (this._overflowObserver) {
      this._overflowObserver.disconnect();
      this._overflowObserver = null;
    }
  },
  watch: {
    syncScroll() {
      this.applySyncScroll();
    },
    readonly() {
      // bytemd 属性重渲染对 CodeMirror 5 实例生效有延迟，直接下发只读状态保证即时锁定
      this.applyReadOnly();
    }
  },
  methods: {
    handleChange(val) {
      // 始终 emit input 保持 v-model 同步，不做规范化（避免干扰 bytemd 内部状态）
      // 空行规范化在发布时处理
      this.$emit("input", val)
      this.$emit("change", val)
    },
    applyReadOnly() {
      this.$nextTick(() => {
        // CodeMirror 5 的实例挂在 `.CodeMirror` DOM 节点上，直接 setOption 即时生效
        const cmEl = this.$el.querySelector('.CodeMirror')
        if (cmEl && cmEl.CodeMirror) {
          cmEl.CodeMirror.setOption('readOnly', this.readonly)
        }
      })
    },
    applySyncScroll() {
      this.$nextTick(() => {
        const bytemdEl = this.$el.querySelector('.bytemd');
        if (bytemdEl) {
          if (this.syncScroll) {
            bytemdEl.classList.remove('bytemd-sync-off');
          } else {
            bytemdEl.classList.add('bytemd-sync-off');
          }
        }
      });
    },
    initScrollIndicator() {
      this.$nextTick(() => {
        const body = this.$el.querySelector('.bytemd-body')
        if (!body) return
        if (body.querySelector('.scroll-indicator')) return

        const editor = body.querySelector('.bytemd-editor')
        const preview = body.querySelector('.bytemd-preview')
        if (!editor || !preview) return

        const indicator = document.createElement('div')
        indicator.className = 'scroll-indicator'
        body.insertBefore(indicator, preview)

        const checkOverflow = () => {
          const editorOverflow = editor.scrollHeight > editor.clientHeight
          const previewOverflow = preview.scrollHeight > preview.clientHeight
          if (editorOverflow || previewOverflow) {
            indicator.style.display = 'block'
          } else {
            indicator.style.display = 'none'
          }
        }

        checkOverflow()
        editor.addEventListener('scroll', checkOverflow)
        preview.addEventListener('scroll', checkOverflow)

        const observer = new MutationObserver(checkOverflow)
        observer.observe(editor, { childList: true, subtree: true, characterData: true })
        observer.observe(preview, { childList: true, subtree: true, characterData: true })
        this._overflowObserver = observer
      })
    },
    async handleUploadImages(files) {
      const result = []
      for (const file of files) {
        try {
          const url = await uploadFile(file)
          result.push({ url, alt: file.name, title: '' })
        } catch (e) {
          console.error('图片上传失败:', e)
        }
      }
      return result
    }
  }
};
</script>

<style scoped>
.byte-md-editor-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  /* 不要 overflow: hidden，否则 sticky 定位和下拉菜单都会失效 */
}
/* bytemd 容器：flex 列布局，toolbar 固定顶部，body 填充剩余 */
.byte-md-editor-wrapper :deep(.bytemd) {
  height: 100% !important;
  min-height: 0;
  border: none !important;
  display: flex !important;
  flex-direction: column !important;
  /* 移除 overflow: hidden，否则 sticky 和下拉菜单都会失效 */
}
/* toolbar：不收缩，始终固定在顶部 */
.byte-md-editor-wrapper :deep(.bytemd-toolbar) {
  flex-shrink: 0 !important;
  position: sticky !important;
  top: 0 !important;
  z-index: 100 !important;
  border-bottom: 1px solid #e4e6eb !important;
  background: #ffffff !important;
  padding: 8px 56px 8px 16px !important;
  overflow: visible !important; /* 确保下拉菜单可见 */
}
/* 工具栏下拉菜单（tippy 弹出层），确保不被裁剪 */
.byte-md-editor-wrapper :deep(.bytemd-toolbar .bytemd-dropdown),
.byte-md-editor-wrapper :deep(.bytemd-tippy) {
  overflow: visible !important;
  z-index: 200 !important;
}
.import-doc-btn {
  position: absolute;
  top: 8px;
  right: 16px;
  z-index: 101;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #515767;
  cursor: pointer;
  transition: background-color 0.2s, color 0.2s;
}
.import-doc-btn:hover {
  background-color: #f2f3f5;
  color: #1e80ff;
}
.byte-md-editor-wrapper :deep(.bytemd-toolbar-icon) {
  width: 32px;
  height: 32px;
  padding: 6px;
  color: #515767;
}
.byte-md-editor-wrapper :deep(.bytemd-toolbar-divider) {
  background-color: #e4e6eb;
  margin: 4px 4px;
}
.byte-md-editor-wrapper :deep(.bytemd-status) {
  display: none;
}
/* body：使用明确高度计算，overflow: hidden 创建独立滚动区域 */
.byte-md-editor-wrapper :deep(.bytemd-body) {
  height: calc(100% - 49px) !important; /* 100% 减去 toolbar 高度 */
  display: flex !important;
  overflow: hidden !important;
  min-height: 0 !important;
  flex: none !important;
}
/* 编辑区：固定宽度，独立滚动 */
.byte-md-editor-wrapper :deep(.bytemd-editor) {
  flex: 0 0 50% !important;
  overflow-y: auto !important;
  overflow-x: hidden !important;
  min-height: 0 !important;
  height: 100% !important;
  border-right: none !important;
}
/* CodeMirror：高度自适应，内部滚动 */
.byte-md-editor-wrapper :deep(.bytemd-editor .CodeMirror) {
  height: 100% !important;
}
.byte-md-editor-wrapper :deep(.bytemd-editor .CodeMirror-scroll) {
  overflow-y: auto !important;
  height: auto !important;
  max-height: none !important;
}
.byte-md-editor-wrapper :deep(.bytemd-editor textarea) {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  font-size: 15px;
  line-height: 1.8;
  padding: 20px 24px;
  color: #252933;
  resize: none;
}
/* 预览区：固定宽度，独立滚动 */
.byte-md-editor-wrapper :deep(.bytemd-preview) {
  flex: 0 0 50% !important;
  overflow-y: auto !important;
  overflow-x: hidden !important;
  min-height: 0 !important;
  height: 100% !important;
  padding: 20px 24px;
  background-color: #ffffff !important;
  color: #24292f !important;
}
.byte-md-editor-wrapper :deep(.bytemd-split) {
  width: 100%;
  height: 100%;
}
.byte-md-editor-wrapper :deep(.markdown-body) {
  font-size: 15px;
  line-height: 1.8;
  background-color: #ffffff !important;
  color: #24292f !important;
}
.byte-md-editor-wrapper :deep(.markdown-body pre),
.byte-md-editor-wrapper :deep(.markdown-body code) {
  background-color: #f6f8fa !important;
  color: #24292f !important;
}
.byte-md-editor-wrapper :deep(.markdown-body p) {
  margin-bottom: 10px;
  margin-top: 0;
}
.byte-md-editor-wrapper :deep(.markdown-body h1),
.byte-md-editor-wrapper :deep(.markdown-body h2),
.byte-md-editor-wrapper :deep(.markdown-body h3),
.byte-md-editor-wrapper :deep(.markdown-body h4) {
  margin-top: 12px;
  margin-bottom: 8px;
  color: #242933;
}
.byte-md-editor-wrapper :deep(.markdown-body table) {
  background-color: #ffffff !important;
  border-collapse: collapse;
  width: 100%;
}
.byte-md-editor-wrapper :deep(.markdown-body table th),
.byte-md-editor-wrapper :deep(.markdown-body table td) {
  border: 1px solid #d0d7de !important;
  padding: 8px 12px;
  color: #24292f !important;
  background-color: #ffffff !important;
}
.byte-md-editor-wrapper :deep(.markdown-body table th) {
  background-color: #f2f3f5 !important;
  font-weight: 600;
}
.byte-md-editor-wrapper :deep(.markdown-body table tr:nth-child(even) td) {
  background-color: #f9f9f9 !important;
}
.byte-md-editor-wrapper :deep(.markdown-body ul) {
  list-style: disc !important;
  padding-left: 24px !important;
  margin: 8px 0;
}
.byte-md-editor-wrapper :deep(.markdown-body ol) {
  list-style: decimal !important;
  padding-left: 24px !important;
  margin: 8px 0;
}
.byte-md-editor-wrapper :deep(.markdown-body ul li),
.byte-md-editor-wrapper :deep(.markdown-body ol li) {
  list-style: inherit !important;
  margin: 4px 0;
  color: #24292f;
}
.byte-md-editor-wrapper :deep(.markdown-body img) {
  max-width: 100%;
  height: auto;
}
/* 滚动指示条：仅内容溢出时显示 */
.byte-md-editor-wrapper :deep(.scroll-indicator) {
  width: 4px;
  min-width: 4px;
  background: #e4e6eb;
  cursor: default;
  flex-shrink: 0;
  display: none;
  transition: background 0.2s;
}
</style>
