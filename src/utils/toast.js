// Simple toast/confirm utilities — replaces weex modal module

var toastTimer = null

export function toast(message, duration) {
  // 移除上一个toast
  if (toastTimer) {
    clearTimeout(toastTimer)
    var existing = document.querySelector('.zhuri-toast')
    if (existing) existing.parentNode.removeChild(existing)
  }
  var div = document.createElement('div')
  div.className = 'zhuri-toast'
  div.textContent = message
  div.style.cssText = [
    'position: fixed',
    'top: 50%',
    'left: 50%',
    'transform: translate(-50%, -50%)',
    'background: rgba(0, 0, 0, 0.78)',
    'color: #fff',
    'padding: 14px 28px',
    'border-radius: 8px',
    'font-size: 16px',
    'z-index: 99999',
    'pointer-events: none',
    'white-space: nowrap',
    'max-width: 80vw',
    'overflow: hidden',
    'text-overflow: ellipsis',
    'box-shadow: 0 4px 12px rgba(0,0,0,0.15)',
    'animation: zhuri-toast-in 0.25s ease'
  ].join(';')
  document.body.appendChild(div)
  toastTimer = setTimeout(function () {
    if (div.parentNode) {
      div.style.opacity = '0'
      div.style.transition = 'opacity 0.3s'
      setTimeout(function () {
        if (div.parentNode) div.parentNode.removeChild(div)
      }, 300)
    }
    toastTimer = null
  }, (duration || 2) * 1000)
}

export function confirmDialog(message, callback) {
  if (window.confirm(message)) {
    callback && callback('OK')
  } else {
    callback && callback('CANCEL')
  }
}

// 注入动画样式
if (typeof document !== 'undefined' && !document.getElementById('zhuri-toast-style')) {
  var style = document.createElement('style')
  style.id = 'zhuri-toast-style'
  style.textContent = '@keyframes zhuri-toast-in{from{opacity:0;transform:translate(-50%,-50%) scale(0.85)}to{opacity:1;transform:translate(-50%,-50%) scale(1)}}'
  document.head.appendChild(style)
}