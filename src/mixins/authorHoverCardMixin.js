/**
 * 作者信息悬浮卡片 —— 悬浮保持逻辑 mixin（参考站内信悬浮框的实现思路）
 *
 * 站内信悬浮框之所以稳定，是因为「铃铛触发区 ∪ 下拉面板」位于同一个容器内，
 * 鼠标在触发区和面板之间移动时不存在“中间空白”，因此面板不会中途消失。
 *
 * 本项目的作者卡片是「共享的 fixed 定位卡片」，与触发元素（头像/昵称）分属
 * 不同 DOM 节点，无法直接复用容器内嵌套。这里通过跟踪鼠标坐标做「几何判定」，
 * 将悬浮区域定义为：
 *   悬浮区域 = 触发元素外扩区 ∪ 卡片本体外扩区 ∪ 二者之间的竖直桥接通道
 * - 只要鼠标坐标落在上述任一区域内，就取消隐藏定时器，卡片保持显示；
 * - 鼠标真正离开区域后，启动 400ms 隐藏定时器（只启动一次，不因其他元素抖动重置）。
 * 这样即使鼠标从头像移向卡片的途中经过文章标题等其他内容，卡片也不会消失，
 * 可以稳定点击「关注 / 私信」按钮。
 */

// 简易矩形工具：getBoundingClientRect 结果的外扩与点命中判断
function expandRect(rect, margin) {
  return {
    left: rect.left - margin,
    top: rect.top - margin,
    right: rect.right + margin,
    bottom: rect.bottom + margin
  }
}

function rectContains(rect, x, y) {
  return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
}

export default {
  data() {
    return {
      // 作者信息悬浮卡片
      showAuthorCard: false,
      authorCardUserId: null,
      authorCardPosition: { top: 0, left: 0, arrow: 'top' },
      authorCardTimer: null,
      // 当前触发悬浮卡片的头像/昵称元素（构成悬浮区域的一部分）
      authorTriggerEl: null,
      // 悬浮区域外扩边距：覆盖触发元素与卡片之间的间隙，形成连续悬浮区域
      authorHoverMargin: 12
    }
  },
  beforeDestroy() {
    this.removeAuthorCardListeners()
    this.clearAuthorCardTimer()
  },
  methods: {
    /**
     * 打开作者信息悬浮卡片
     * @param {Number|String} userId 作者用户ID
     * @param {Event} event 触发事件的 DOM 事件（用于计算定位与记录触发元素）
     */
    showAuthorHoverCard(userId, event) {
      if (!userId || !event) return
      this.cancelAuthorCardHide()
      this.authorCardUserId = userId
      this.authorTriggerEl = event.target || null
      this.authorCardPosition = this.computeAuthorCardPosition(event)
      this.showAuthorCard = true
      this.addAuthorCardListeners()
    },

    // 根据触发元素位置计算卡片定位：默认显示在触发元素下方 8px，下方空间不足时翻转到上方
    computeAuthorCardPosition(event) {
      var rect = event.target.getBoundingClientRect()
      var cardHeight = 300 // 卡片预估高度（宽 232px 时实测约 300px），用于下方空间不足时翻转
      var cardTop = rect.bottom + 8
      var arrow = 'top'
      if (cardTop + cardHeight > window.innerHeight - 12) {
        cardTop = rect.top - cardHeight - 8
        arrow = 'bottom'
      }
      if (cardTop < 8) cardTop = 8
      var cardLeft = rect.left
      var maxLeft = window.innerWidth - 250
      if (cardLeft > maxLeft) cardLeft = maxLeft
      if (cardLeft < 8) cardLeft = 8
      return { top: cardTop, left: cardLeft, arrow: arrow }
    },

    addAuthorCardListeners() {
      this.removeAuthorCardListeners()
      document.addEventListener('mousemove', this.onAuthorCardDocMouseMove, true)
    },

    removeAuthorCardListeners() {
      document.removeEventListener('mousemove', this.onAuthorCardDocMouseMove, true)
    },

    // 全局鼠标坐标跟踪：指针落在悬浮区域内则保持显示，离开后延迟隐藏
    onAuthorCardDocMouseMove(e) {
      if (!this.showAuthorCard) return
      if (this.isPointerInAuthorHoverRegion(e.clientX, e.clientY)) {
        this.cancelAuthorCardHide()
      } else if (!this.authorCardTimer) {
        this.scheduleAuthorCardHide()
      }
    },

    // 判断指针坐标是否落在悬浮区域内（触发元素外扩区 ∪ 卡片外扩区 ∪ 竖直桥接通道）
    isPointerInAuthorHoverRegion(x, y) {
      var triggerRect = this.getAuthorTriggerRect()
      var cardRect = this.getAuthorCardRect()
      if (triggerRect && rectContains(expandRect(triggerRect, this.authorHoverMargin), x, y)) return true
      if (cardRect && rectContains(expandRect(cardRect, this.authorHoverMargin), x, y)) return true
      // 竖直桥接通道：触发元素与卡片水平重叠、竖直方向位于二者之间的区域，
      // 保证鼠标在间隙中移动时卡片不消失（等价于站内信容器内的“连接区”）
      if (triggerRect && cardRect) {
        var xMin = Math.max(triggerRect.left, cardRect.left)
        var xMax = Math.min(triggerRect.right, cardRect.right)
        var yTop = Math.min(triggerRect.bottom, cardRect.bottom)
        var yBottom = Math.max(triggerRect.top, cardRect.top)
        if (xMin <= xMax && y >= yTop && y <= yBottom && x >= xMin && x <= xMax) return true
      }
      return false
    },

    // 当前触发元素的外接矩形（卡片打开期间触发元素不会移动，直接读即可）
    getAuthorTriggerRect() {
      var el = this.authorTriggerEl
      if (!el || !el.getBoundingClientRect) return null
      return el.getBoundingClientRect()
    },

    // 当前卡片本体的外接矩形
    getAuthorCardRect() {
      var cardEl = this.$refs.authorHoverCard && this.$refs.authorHoverCard.$el
      if (!cardEl || !cardEl.getBoundingClientRect) return null
      return cardEl.getBoundingClientRect()
    },

    cancelAuthorCardHide() {
      if (this.authorCardTimer) {
        clearTimeout(this.authorCardTimer)
        this.authorCardTimer = null
      }
    },

    clearAuthorCardTimer() {
      if (this.authorCardTimer) {
        clearTimeout(this.authorCardTimer)
        this.authorCardTimer = null
      }
    },

    // 延迟隐藏：鼠标离开悬浮区域 400ms 后关闭卡片
    scheduleAuthorCardHide() {
      var self = this
      this.cancelAuthorCardHide()
      this.authorCardTimer = setTimeout(function () {
        self.hideAuthorHoverCard()
      }, 400)
    },

    hideAuthorHoverCard() {
      this.showAuthorCard = false
      this.authorCardTimer = null
      this.authorTriggerEl = null
      this.removeAuthorCardListeners()
    },

    // 触发元素 mouseleave（来自子组件 author-leave 事件），几何跟踪会自动兜底
    onAuthorLeave() {
      this.scheduleAuthorCardHide()
    },

    // 卡片 mouseenter：取消隐藏，保证可点击卡片内按钮
    onAuthorCardEnter() {
      this.cancelAuthorCardHide()
    },

    // 卡片 mouseleave：延迟隐藏
    onAuthorCardLeave() {
      this.scheduleAuthorCardHide()
    },

    // 点击卡片外部 / 关闭卡片
    closeAuthorHoverCard() {
      this.hideAuthorHoverCard()
    },

    // 点击头像/昵称 -> 跳转目标用户个人主页
    goToUserHome(userId) {
      this.hideAuthorHoverCard()
      if (!userId) return
      this.$router.push('/user/' + userId)
    }
  }
}
