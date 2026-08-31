<template>
  <div class="search-result-tag">
    <!-- 左：# 标签图标 -->
    <div class="tag-badge">#</div>

    <!-- 中：标签名 + 统计 -->
    <div class="tag-info" @click="onOpen">
      <span class="tag-name" v-html="displayName"></span>
      <span class="tag-meta">
        {{ data.postArticleCount || 0 }} 篇文章
        <template v-if="data.concernUserCount"> · {{ data.concernUserCount }} 人关注</template>
      </span>
    </div>

    <!-- 右：订阅按钮 -->
    <button
      class="btn-subscribe"
      :class="{ 'is-subscribed': subscribed }"
      @click="onSubscribe"
    >{{ subscribed ? '已订阅' : '+ 订阅' }}</button>
  </div>
</template>

<script>
import { highlight } from '../../utils/sanitize.js'

export default {
  name: 'SearchResultTag',
  props: {
    data: {
      type: Object,
      required: true
    },
    keyword: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      subscribed: false
    }
  },
  computed: {
    displayName() {
      return highlight(this.data.name || this.data.title, this.keyword)
    }
  },
  methods: {
    onOpen() {
      this.$emit('open', this.data.name || this.data.title)
    },
    onSubscribe() {
      this.$emit('subscribe', this.data.name || this.data.title, this.subscribed)
    }
  }
}
</script>

<style lang="less" scoped>
.search-result-tag {
  display: flex;
  align-items: center;
  gap: 14px;
  background-color: #fff;
  border: 1px solid #f0f1f5;
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 12px;
  transition: box-shadow 0.25s ease;
}

.search-result-tag:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.tag-badge {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  border-radius: 8px;
  background: linear-gradient(135deg, #1e80ff 0%, #4ba1ff 100%);
  color: #fff;
  font-size: 26px;
  font-weight: 700;
  line-height: 48px;
  text-align: center;
  user-select: none;
}

.tag-info {
  flex: 1;
  min-width: 0;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tag-name {
  font-size: 16px;
  font-weight: 600;
  color: #252933;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-name /deep/ em {
  color: #f53f3f;
  font-style: normal;
  background-color: #fff1f0;
  padding: 0 2px;
  border-radius: 2px;
}

.tag-name:hover {
  color: #1e80ff;
}

.tag-meta {
  font-size: 12px;
  color: #8a93a6;
}

.btn-subscribe {
  flex-shrink: 0;
  font-size: 13px;
  color: #1e80ff;
  background-color: #e8f3ff;
  border: none;
  border-radius: 4px;
  padding: 6px 14px;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.btn-subscribe:hover {
  background-color: #1e80ff;
  color: #fff;
}

.btn-subscribe.is-subscribed {
  color: #515767;
  background-color: #f2f3f5;
  cursor: default;
}

@media screen and (max-width: 767px) {
  .search-result-tag {
    padding: 12px;
    border-radius: 0;
    margin-bottom: 0;
    border: none;
    border-bottom: 1px solid #f0f1f5;
  }

  .tag-badge {
    width: 40px;
    height: 40px;
    font-size: 22px;
    line-height: 40px;
  }
}
</style>