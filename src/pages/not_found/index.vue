<template>
  <div class="not-found-page">
    <div class="nf-card">
      <div class="nf-code">404</div>
      <div class="nf-title">页面不存在或已被删除</div>
      <div class="nf-desc">您访问的链接可能已失效，或该内容已被作者删除</div>
      <div class="nf-actions">
        <span class="nf-btn primary" @click="goHome">回到首页</span>
        <span class="nf-btn" @click="goBack">返回上一页</span>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'NotFound',
  methods: {
    goHome() {
      this.$router.push('/home')
    },
    goBack() {
      if (window.history.length > 1) {
        this.$router.back()
      } else {
        this.$router.push('/home')
      }
    }
  }
}
</script>

<style lang="less" scoped>
.not-found-page {
  width: 100%;
  height: 100vh;
  min-height: 480px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f4f5f5;
  padding: 24px;
  box-sizing: border-box;
}
.nf-card {
  text-align: center;
  padding: 48px 56px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
  max-width: 480px;
  width: 100%;
}
.nf-code {
  font-size: 72px;
  font-weight: 700;
  color: #1E80FF;
  line-height: 1;
  margin-bottom: 16px;
}
.nf-title {
  font-size: 18px;
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 8px;
}
.nf-desc {
  font-size: 14px;
  color: #8a919f;
  margin-bottom: 28px;
}
.nf-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
}
.nf-btn {
  display: inline-block;
  padding: 8px 24px;
  border-radius: 20px;
  font-size: 14px;
  cursor: pointer;
  border: 1px solid #e4e6eb;
  color: #515767;
  transition: all 0.2s;
  user-select: none;
}
.nf-btn:hover {
  border-color: #1E80FF;
  color: #1E80FF;
}
.nf-btn.primary {
  background: #1E80FF;
  border-color: #1E80FF;
  color: #fff;
}
.nf-btn.primary:hover {
  background: #1273ef;
}
</style>
