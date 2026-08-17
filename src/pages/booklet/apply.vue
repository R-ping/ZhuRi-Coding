<template>
  <div class="booklet-apply-page">
    <div class="apply-header">
      <h1 class="apply-title">申请成为小册作家</h1>
      <p class="apply-subtitle">填写基础信息与小册计划，提交后进入审核（7~15 个工作日）</p>
    </div>

    <el-form
      ref="form"
      :model="form"
      :rules="rules"
      label-width="120px"
      class="apply-form"
      :disabled="submitting"
    >
      <div class="form-group">
        <h3 class="group-title">基础信息</h3>

        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="请输入姓名" :maxlength="64" />
        </el-form-item>

        <el-form-item label="个人职位" prop="position">
          <el-input v-model="form.position" placeholder="如：后端工程师 / 技术博主" :maxlength="128" />
        </el-form-item>

        <el-form-item label="个人履历" prop="resume">
          <el-input type="textarea" v-model="form.resume" :rows="4" placeholder="简述技术方向、从业经历与代表作" />
        </el-form-item>

        <el-form-item label="联系方式" prop="contactWechat" required>
          <div class="contact-row">
            <el-input v-model="form.contactWechat" placeholder="微信号" :maxlength="64" class="contact-wechat" />
            <el-input v-model="form.contactEmail" placeholder="常用邮箱" :maxlength="128" class="contact-email" />
          </div>
        </el-form-item>

        <el-form-item label="博客/媒体" prop="blogs">
          <el-input v-model="form.blogs" placeholder="掘金账号及其他博客/技术媒体（多个用顿号分隔）" :maxlength="500" />
        </el-form-item>
      </div>

      <div class="form-group">
        <h3 class="group-title">小册信息</h3>

        <el-form-item label="小册主题" prop="title">
          <el-input v-model="form.title" placeholder="20 字以内，聚焦明确的主题" :maxlength="20" show-word-limit />
        </el-form-item>

        <el-form-item label="小册介绍" prop="intro">
          <el-input type="textarea" v-model="form.intro" :rows="3" placeholder="Why / What / How：为什么写、写什么、怎么组织" />
        </el-form-item>

        <el-form-item label="目标人群" prop="target">
          <el-input v-model="form.target" placeholder="主要面向哪些读者" />
        </el-form-item>

        <el-form-item label="小册大纲" prop="outline">
          <el-input type="textarea" v-model="form.outline" :rows="3" placeholder="一级标题（建议 15~40 节）或文档链接" />
        </el-form-item>

        <el-form-item label="写作进度" prop="progress">
          <el-input v-model="form.progress" placeholder="当前写作进度与更新频率" />
        </el-form-item>

        <el-form-item label="样章试读" prop="samples">
          <el-input type="textarea" v-model="form.samples" :rows="2" placeholder="3 篇以上样章（每篇 1500 字以上）或样章链接" />
        </el-form-item>

        <el-form-item label="申请渠道" prop="channel">
          <el-select v-model="form.channel" placeholder="请选择申请渠道" style="width: 100%">
            <el-option
              v-for="c in channels"
              :key="c"
              :label="c"
              :value="c"
            />
          </el-select>
        </el-form-item>
      </div>

      <div class="form-actions">
        <el-button @click="$router.push('/booklet/rules')">返回</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">提交申请</el-button>
      </div>
    </el-form>
  </div>
</template>

<script>
import courseApi from '@/apis/course'

const CHANNELS = [
  '掘金小册微信公众号',
  '《如何写一本掘金小册》小册文章',
  '小册姐微信',
  '掘金社区LV7级及以上用户',
  '经推荐人介绍和推荐',
  '其他'
]

const EMPTY_FORM = () => ({
  // 基础信息
  realName: '',
  position: '',
  resume: '',
  contactWechat: '',
  contactEmail: '',
  blogs: '',
  // 小册信息
  title: '',
  intro: '',
  target: '',
  outline: '',
  progress: '',
  samples: '',
  channel: ''
})

export default {
  name: 'BookletApply',
  data() {
    return {
      channels: CHANNELS,
      form: EMPTY_FORM(),
      submitting: false,
      rules: {
        realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
        position: [{ required: true, message: '请输入个人职位', trigger: 'blur' }],
        resume: [{ required: true, message: '请输入个人履历', trigger: 'blur' }],
        contactWechat: [{ required: true, message: '请填写至少一项联系方式', trigger: 'blur' }],
        title: [
          { required: true, message: '请输入小册主题', trigger: 'blur' },
          { max: 20, message: '主题不能超过 20 字', trigger: 'blur' }
        ],
        intro: [{ required: true, message: '请输入小册介绍', trigger: 'blur' }],
        channel: [{ required: true, message: '请选择申请渠道', trigger: 'change' }]
      }
    }
  },
  mounted() {
    this.loadProfile()
  },
  methods: {
    // 回填上次填写的基础信息（姓名/职位/履历/联系方式/博客），允许修改覆盖
    async loadProfile() {
      try {
        const res = await courseApi.getAuthorProfile()
        if (res && res.code === 200 && res.data) {
          const p = res.data
          this.form.realName = p.realName || ''
          this.form.position = p.position || ''
          this.form.resume = p.resume || ''
          this.form.contactWechat = p.contactWechat || ''
          this.form.contactEmail = p.contactEmail || ''
          this.form.blogs = p.blogs || ''
        }
      } catch (e) {
        // 静默：未登录或接口异常时使用空表单
      }
    },
    onSubmit() {
      this.$refs.form.validate(async valid => {
        if (!valid) return
        this.submitting = true
        try {
          // 小册申请：courseId 为 null 时后端会自动创建草稿并联通申请资格（未达 Lv7 也可提交）
          const courseId = this.$route.query.courseId ? Number(this.$route.query.courseId) : null

          // 基础信息（独立字段）
          const profile = {
            realName: this.form.realName,
            position: this.form.position,
            resume: this.form.resume,
            contactWechat: this.form.contactWechat,
            contactEmail: this.form.contactEmail,
            blogs: this.form.blogs
          }
          // 申请单 JSON（含主题/介绍/目标/大纲/进度/样章/渠道/联系方式）
          const applyContent = {
            title: this.form.title,
            intro: this.form.intro,
            target: this.form.target,
            outline: this.form.outline,
            progress: this.form.progress,
            samples: this.form.samples,
            channel: this.form.channel,
            contactWechat: this.form.contactWechat,
            contactEmail: this.form.contactEmail,
            blogs: this.form.blogs
          }
          const res = await courseApi.applyBooklet({
            courseId,
            applyContent: JSON.stringify(applyContent),
            authorProfile: profile
          })
          if (res && res.code === 200) {
            this.$message.success('申请已提交，请等待审核')
            this.$router.push('/creator/booklet')
          } else {
            this.$message.error((res && res.errorMessage) || '提交失败，请重试')
          }
        } catch (e) {
          this.$message.error('提交失败，请重试')
        } finally {
          this.submitting = false
        }
      })
    }
  }
}
</script>

<style lang="less" scoped>
.booklet-apply-page {
  min-height: 100vh;
  background-color: #f4f5f7;
  padding-bottom: 60px;

  .apply-header {
    background: linear-gradient(135deg, #1e80ff 0%, #4a90ff 100%);
    color: #fff;
    padding: 40px 24px 32px;

    .apply-title {
      font-size: 28px;
      font-weight: 700;
    }
    .apply-subtitle {
      font-size: 14px;
      opacity: 0.9;
      margin-top: 8px;
    }
  }

  .apply-form {
    max-width: 720px;
    margin: 24px auto 0;
    background: #fff;
    border-radius: 12px;
    padding: 24px 32px 32px;

    .group-title {
      font-size: 16px;
      font-weight: 600;
      color: #252933;
      border-bottom: 1px solid #f0f1f5;
      padding-bottom: 12px;
      margin-bottom: 20px;
    }
    .contact-row {
      display: flex;
      gap: 12px;
      width: 100%;
    }
  }

  .form-actions {
    text-align: center;
    margin-top: 16px;
    .el-button {
      width: 140px;
    }
  }
}
</style>