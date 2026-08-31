function getFrontendRedirectUri() {
  // 优先使用浏览器宿主环境传入的 OAuth 回调地址（vite 本地调试为 .env 的 OAUTH_REDIRECT_URI）
  var origin = window.__OAUTH_REDIRECT__ || 'https://195b7e5b.r40.cpolar.top'
  return origin + '/oauth/callback'
}

const config = {
  weibo: {
    clientId: '3770872274',
    redirectUri: getFrontendRedirectUri(),
    authorizeUrl: 'https://api.weibo.com/oauth2/authorize',
    responseType: 'code'
  },
  github: {
    clientId: 'Ov23liBlhpjPoc6XKPbf',
    redirectUri: getFrontendRedirectUri(),
    authorizeUrl: 'https://github.com/login/oauth/authorize',
    scope: 'user',
    responseType: 'code'
  },
  wechat: {
    // 微信公众号扫码登录 — 展示公众号二维码，用户扫码后发送验证码
    qrcodeUrl: '/static/images/gzh.jpeg',
    platform: 'wechat'
  }
}

/**
 * 获取OAuth授权跳转URL
 * @param {string} platform - 'github' | 'weibo'
 * @returns {string} 完整的OAuth授权URL
 *
 * 说明：发起授权时为本次登录生成【随机 state】并保存到会话存储，
 * 第三方授权完成后会原样带回 state，回调页据此校验，防止"登录 CSRF"
 * （攻击者用自己账号换取的 code 诱导受害者回调，导致绑定/登录到攻击者账号）。
 * 旧调用不带随机 state 时仍返回可用的授权 URL（兼容）。
 */
export function getOAuthUrl(platform) {
  var cfg = config[platform]
  if (!cfg) return '#'
  if (platform === 'wechat') return cfg.qrcodeUrl
  var randomState = platform + ':' + Math.random().toString(36).slice(2) + Date.now().toString(36)
  try { window.sessionStorage.setItem('oauth_state', randomState) } catch (e) { /* 隐私模式忽略 */ }
  var qs = 'client_id=' + cfg.clientId +
    '&redirect_uri=' + encodeURIComponent(cfg.redirectUri) +
    '&response_type=' + cfg.responseType +
    '&scope=' + cfg.scope +
    '&state=' + encodeURIComponent(randomState)
  return cfg.authorizeUrl + '?' + qs
}

export default config