/**
 * XSS 安全工具 - 基于 DOMPurify 的 HTML 内容净化
 * 所有 v-html 渲染前必须经过此函数 sanitize
 */
import DOMPurify from 'dompurify'

// 配置：仅允许安全标签和属性
const ALLOWED_TAGS = [
  'p', 'br', 'strong', 'b', 'em', 'i', 'u', 's', 'del',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li', 'blockquote', 'pre', 'code',
  'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'div', 'span', 'hr', 'font', 'sub', 'sup',
  'figure', 'figcaption', 'video', 'source'
]

const ALLOWED_ATTRS = [
  'href', 'target', 'rel', 'src', 'alt', 'width', 'height',
  'class', 'id', 'style', 'color', 'align',
  'controls', 'autoplay', 'loop', 'muted', 'poster'
]

const purifyConfig = {
  ALLOWED_TAGS,
  ALLOWED_ATTRS,
  ALLOW_DATA_ATTR: false,
  ALLOW_UNKNOWN_PROTOCOLS: false
}

/**
 * 净化 HTML 内容，移除 XSS 攻击向量
 * @param {string} html - 原始 HTML 字符串
 * @returns {string} - 安全的 HTML 字符串
 */
export function sanitizeHtml(html) {
  if (!html || typeof html !== 'string') return ''
  return DOMPurify.sanitize(html, purifyConfig)
}

/**
 * 净化搜索结果高亮文本（仅允许 <em> 和 <font> 标签）
 * @param {string} html - 包含高亮标签的 HTML
 * @returns {string} - 安全的高亮 HTML
 */
export function sanitizeHighlight(html) {
  if (!html || typeof html !== 'string') return ''
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['em', 'font'],
    ALLOWED_ATTRS: ['color', 'style']
  })
}

/**
 * 对纯文本进行 HTML 转义，防止 v-html 注入
 * @param {string} s - 原始文本
 * @returns {string} - 转义后的安全文本
 */
function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 安全关键词高亮：先转义文本，再将命中关键词包裹为 <em>（配合 v-html 渲染）
 * 数据来源于用户输入的标题/昵称等纯文本，非 HTML，故必须先转义再替换。
 * @param {string} text - 原始纯文本
 * @param {string} keyword - 高亮关键词（可为空）
 * @returns {string} - 富含 <em> 的安全 HTML 字符串
 */
export function highlight(text, keyword) {
  if (!text) return ''
  const esc = escapeHtml(text)
  if (!keyword) return esc
  const kw = escapeHtml(keyword)
  if (!kw) return esc
  // 转义正则元字符，避免关键词含特殊符号导致正则异常
  const safe = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return esc.replace(new RegExp('(' + safe + ')', 'gi'), '<em>$1</em>')
}

export default { sanitizeHtml, sanitizeHighlight, highlight }