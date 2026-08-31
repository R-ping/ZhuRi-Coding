<template>
  <div class="callback-page">
    <div class="callback-card">
      <div class="status-icon" v-if="status === 'processing'">
        <span class="spinner"></span>
      </div>
      <div class="status-icon success" v-else-if="status === 'success'">
        <span class="checkmark">&#10003;</span>
      </div>
      <div class="status-icon error" v-else>
        <span class="cross">&#10005;</span>
      </div>
      <p class="status-text">{{ statusMessage }}</p>
    </div>
  </div>
</template>

<script>
import conf from '@/common/conf'

export default {
  name: 'OAuthCallback',
  data() {
    return {
      status: 'processing',
      statusMessage: '正在登录...'
    }
  },
  created() {
    this.handleCallback()
  },
  methods: {
    handleCallback: function () {
      var self = this
      var code = this.$route.query.code
      var state = this.$route.query.state
      var expectedState = ''
      try { expectedState = window.sessionStorage.getItem('oauth_state') || '' } catch (e) { /* 忽略 */ }

      // 平台：随机 state 以 "platform:随机串" 形式携带，可从中解析；否则回退到 query/path 推断
      var platform = null
      if (state && String(state).indexOf(':') > -1) {
        platform = String(state).split(':')[0]
      }
      if (!platform) {
        platform = this.$route.query.platform || this.getPlatformFromPath()
      }

      if (!code) {
        this.status = 'error'
        this.statusMessage = '缺少授权码，请重试'
        setTimeout(function () { self.$router.replace('/home') }, 2000)
        return
      }
      if (!platform) {
        this.status = 'error'
        this.statusMessage = '未知平台，请重试'
        setTimeout(function () { self.$router.replace('/home') }, 2000)
        return
      }

      // ★ CSRF 校验：本次授权带回了随机 state，必须与发起时会话保存的一致
      // （攻击者无法预知受害者会话中的随机 state，可拦截"用他人 code 诱导登录/绑定"）
      if (expectedState) {
        window.sessionStorage.removeItem('oauth_state')
        if (String(state) !== expectedState) {
          this.status = 'error'
          this.statusMessage = '授权校验失败，请重新发起登录'
          setTimeout(function () { self.$router.replace('/home') }, 2000)
          return
        }
      }

      // 调用后端OAuth回调接口（通过网关转发到user微服务）
      var url = '/user/oauth2/code/' + platform

      // 使用 fetch 直接调用（绕过 request.js 的 token 逻辑）
      fetch(url + '?code=' + encodeURIComponent(code), {
        method: 'GET',
        headers: { 'Content-Type': 'application/json; charset=UTF-8' }
      })
        .then(function (response) { return response.json() })
        .then(function (d) {
          if (d && d.code === 200 && d.data) {
            var result = d.data
            if (result.status === 'need_bind') {
              // 未绑定：存储社交信息，跳转首页并弹出绑定弹窗
              self.$store.dispatch('setSocialBindInfo', {
                platform: result.platform,
                platformUid: result.platformUid
              })
              self.$store.dispatch('showSocialBind')
              self.$router.replace('/home')
            } else {
              // 已绑定：直接登录
              self.$store.dispatch('login', result)
              self.status = 'success'
              self.statusMessage = '登录成功'
              setTimeout(function () { self.$router.replace('/home') }, 1500)
            }
          } else {
            self.status = 'error'
            self.statusMessage = (d && d.errorMessage) || '登录失败，请重试'
            setTimeout(function () { self.$router.replace('/home') }, 2000)
          }
        })
        .catch(function () {
          self.status = 'error'
          self.statusMessage = '网络请求失败，请重试'
          setTimeout(function () { self.$router.replace('/home') }, 2000)
        })
    },
    getPlatformFromPath: function () {
      var path = this.$route.path || ''
      if (path.indexOf('github') !== -1) return 'github'
      if (path.indexOf('weibo') !== -1) return 'weibo'
      return null
    }
  }
}
</script>

<style scoped>
.callback-page {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #ffffff;
  z-index: 10000;
}
.callback-card { text-align: center; }
.status-icon {
  width: 80px; height: 80px;
  margin: 0 auto 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.spinner {
  width: 40px; height: 40px;
  border: 4px solid #e0e0e0;
  border-top-color: #3194ff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.checkmark, .cross {
  font-size: 48px;
  font-weight: bold;
}
.status-icon.success .checkmark { color: #52c41a; }
.status-icon.error .cross { color: #ff4d4f; }
.status-text {
  font-size: 18px;
  color: #666666;
  margin: 0;
}
</style>