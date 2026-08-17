function getFrontendRedirectUri() {
  // var origin = typeof window !== 'undefined' ? window.location.origin : ''
  var origin = "https://195b7e5b.r40.cpolar.top"
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
 */
export function getOAuthUrl(platform) {
  var cfg = config[platform]
  if (!cfg) return '#'
  if (platform === 'github') {
    return cfg.authorizeUrl + '?client_id=' + cfg.clientId +
      '&redirect_uri=' + encodeURIComponent(cfg.redirectUri) +
      '&scope=' + cfg.scope +
      '&response_type=' + cfg.responseType +
      '&state=' + platform
  }
  if (platform === 'weibo') {
    return cfg.authorizeUrl + '?client_id=' + cfg.clientId +
      '&redirect_uri=' + encodeURIComponent(cfg.redirectUri) +
      '&response_type=' + cfg.responseType +
      '&state=' + platform
  }
  return '#'
}

export default config