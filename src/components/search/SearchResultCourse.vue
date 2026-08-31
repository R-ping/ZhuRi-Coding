<template>
  <div class="search-result-course" @click="onOpen">
    <!-- 左：小册竖版封面 -->
    <div class="course-cover">
      <img
        v-if="data.coverImage"
        :src="data.coverImage"
        alt="cover"
        class="cover-image"
      />
      <span v-else class="cover-placeholder">小册</span>
    </div>

    <!-- 右：信息区 -->
    <div class="course-info">
      <h3 class="course-title" v-html="displayTitle"></h3>
      <p class="course-subtitle" v-if="data.subtitle">{{ data.subtitle }}</p>

      <div class="course-meta">
        <span class="course-author">
          <img
            v-if="data.authorAvatar"
            class="author-avatar"
            :src="data.authorAvatar"
            alt="author"
          />
          <span class="author-name">{{ data.authorName || '匿名作者' }}</span>
        </span>
        <span class="course-stats">
          <template v-if="data.chapterCount">{{ data.chapterCount }} 小结</template>
          <template v-if="data.studyCount">
            <template v-if="data.chapterCount"> · </template>{{ data.studyCount }} 人学习
          </template>
        </span>
      </div>
    </div>

    <!-- 右下：价格 -->
    <div class="course-price">
      <span v-if="isFree" class="price-free">免费</span>
      <template v-else>
        <span class="price-currency">¥</span>{{ displayPrice }}
      </template>
    </div>
  </div>
</template>

<script>
import { highlight } from '../../utils/sanitize.js'

export default {
  name: 'SearchResultCourse',
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
  computed: {
    displayTitle() {
      return highlight(this.data.title, this.keyword)
    },
    displayPrice() {
      var p = Number(this.data.price || 0)
      // 整数不显示小数
      return Number.isInteger(p) ? String(p) : p.toFixed(2)
    },
    isFree() {
      return !(Number(this.data.price) > 0)
    }
  },
  methods: {
    onOpen() {
      this.$emit('open', this.data.id)
    }
  }
}
</script>

<style lang="less" scoped>
.search-result-course {
  display: flex;
  align-items: flex-start;
  background-color: #fff;
  border: 1px solid #f0f1f5;
  border-radius: 8px;
  padding: 14px;
  margin-bottom: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  transition: box-shadow 0.25s ease, transform 0.25s ease;
  cursor: pointer;
}

.search-result-course:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  transform: translateY(-1px);
}

.course-cover {
  width: 96px;
  height: 72px;
  flex-shrink: 0;
  border-radius: 4px;
  overflow: hidden;
  background-color: #f2f3f5;
  margin-right: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cover-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-placeholder {
  font-size: 14px;
  color: #c2c8d1;
}

.course-info {
  flex: 1;
  min-width: 0;
}

.course-title {
  font-size: 16px;
  font-weight: 600;
  color: #252933;
  line-height: 1.4;
  margin: 0 0 6px;
  padding: 0;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.course-title /deep/ em {
  color: #f53f3f;
  font-style: normal;
  background-color: #fff1f0;
  padding: 0 2px;
  border-radius: 2px;
}

.course-title:hover {
  color: #1e80ff;
}

.course-subtitle {
  font-size: 13px;
  color: #86909c;
  line-height: 1.5;
  margin: 0 0 10px;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.course-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.course-author {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.author-avatar {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  object-fit: cover;
  background-color: #f2f3f5;
  flex-shrink: 0;
}

.author-name {
  font-size: 13px;
  color: #515767;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.course-stats {
  font-size: 12px;
  color: #8a93a6;
  flex-shrink: 0;
}

.course-price {
  flex-shrink: 0;
  align-self: flex-end;
  margin-left: 12px;
  font-size: 16px;
  font-weight: 600;
  color: #1e80ff;
  white-space: nowrap;
}

.price-currency {
  font-size: 12px;
}

.price-free {
  font-size: 14px;
  color: #00b96b;
  font-weight: 500;
}

@media screen and (max-width: 767px) {
  .search-result-course {
    padding: 12px;
    border-radius: 0;
    margin-bottom: 0;
    border: none;
    border-bottom: 1px solid #f0f1f5;
    box-shadow: none;
  }

  .course-cover {
    width: 72px;
    height: 54px;
  }

  .course-title {
    font-size: 15px;
  }
}
</style>