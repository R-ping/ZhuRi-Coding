<template>
  <div class="booklet-rules-page">
    <div class="rules-hero">
      <div class="hero-badge">掘金小册</div>
      <h1 class="hero-title">成为作家</h1>
      <p class="hero-subtitle">沉淀技术经验，把知识变成一本有读者、有收入的小册</p>
      <el-button type="primary" size="medium" class="hero-apply-btn" @click="goApply">去申请</el-button>
    </div>

    <div class="rules-body">
      <div class="rules-section">
        <h2 class="section-title">申请门槛</h2>
        <p class="section-desc">
          达到 <b>逐力值 Lv7</b> 即可申请；未达标者经申请审核通过后，也可为你开通写小册权限。
        </p>
      </div>

      <div class="rules-section">
        <h2 class="section-title">审核周期</h2>
        <p class="section-desc">申请提交后约 <b>7 ~ 15 个工作日</b> 完成审核，结果会同步到小册站。</p>
      </div>

      <div class="rules-section">
        <h2 class="section-title">作家权益</h2>
        <p class="section-desc">
          已服务 <b>·</b> 位读者、累计为作家创造 <b>·</b> 元收益，加入我们一起把好知识变成好作品。
        </p>
      </div>

      <div class="rules-section">
        <h2 class="section-title">写作要求</h2>
        <ul class="rule-list">
          <li>小册主题需聚焦，20 字以内，有明确的定位与受众。</li>
          <li>大纲建议 15 ~ 40 个一级小节，内容完整、示例可运行。</li>
          <li>申请时提供 3 篇以上样章（每篇 1500 字以上）或样章链接。</li>
        </ul>
      </div>

      <div class="rules-footer">
        <el-button type="primary" size="medium" @click="goApply">去申请</el-button>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'BookletRules',
  methods: {
    goApply() {
      this.$router.push('/booklet/apply')
    }
  }
}
</script>

<style lang="less" scoped>
.booklet-rules-page {
  min-height: 100vh;
  background-color: #f4f5f7;

  .rules-hero {
    text-align: center;
    padding: 60px 24px 48px;
    background: linear-gradient(135deg, #1e80ff 0%, #4a90ff 100%);
    color: #fff;

    .hero-badge {
      display: inline-block;
      padding: 4px 16px;
      border-radius: 14px;
      background: rgba(255, 255, 255, 0.2);
      font-size: 13px;
      margin-bottom: 16px;
    }
    .hero-title {
      font-size: 34px;
      font-weight: 700;
      margin-bottom: 12px;
    }
    .hero-subtitle {
      font-size: 15px;
      opacity: 0.9;
      margin-bottom: 28px;
    }
    .hero-apply-btn {
      width: 160px;
    }
  }

  .rules-body {
    max-width: 760px;
    margin: 0 auto;
    padding: 24px 20px 60px;

    .rules-section {
      background: #fff;
      border-radius: 12px;
      padding: 20px 24px;
      margin-bottom: 16px;

      .section-title {
        font-size: 17px;
        font-weight: 600;
        color: #252933;
        margin-bottom: 10px;
      }
      .section-desc {
        font-size: 14px;
        color: #515767;
        line-height: 1.8;
      }
    }
    .rule-list {
      padding-left: 20px;
      li {
        font-size: 14px;
        color: #515767;
        line-height: 2;
      }
    }
    .rules-footer {
      text-align: center;
      margin-top: 8px;
      .el-button {
        width: 160px;
      }
    }
  }
}
</style>