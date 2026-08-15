// 临时脚本：从参考资料中挑选12篇文档，生成课程章节内容 JSON
const fs = require('fs')
const path = require('path')

const base = path.join(__dirname, '参考资料')

// 精选 12 篇文档及其章节标题
const picks = [
  { file: '沸点功能分析.md', title: '沸点功能深度剖析' },
  { file: '沸点详情页——页面整体架构与元素清单.md', title: '沸点详情页：页面整体架构与元素清单' },
  { file: '沸点详情页——交互行为深度拆解与前端实现方案.md', title: '沸点详情页交互行为深度拆解' },
  { file: '沸点圈子功能分析.md', title: '沸点圈子功能分析' },
  { file: '掘金的沸点圈子分类response.md', title: '沸点圈子分类体系' },
  { file: '话题功能前后端实现分析.md', title: '话题功能前后端实现分析' },
  { file: '稀土文章列表response.md', title: '稀土文章列表响应结构' },
  { file: '文章详情页response.md', title: '文章详情页响应结构' },
  { file: '文章推荐服务——全局公平与多样性配额设计方案.md', title: '文章推荐服务：全局公平与多样性配额设计' },
  { file: '掘友等级参考.md', title: '掘友等级体系' },
  { file: '掘力值参考.md', title: '掘力值体系' },
  { file: '创作话题和创作活动参考.md', title: '创作话题与创作活动' }
]

const chapters = picks.map((p, i) => {
  const raw = fs.readFileSync(path.join(base, p.file), 'utf8')
  // 清理：移除行尾空白，压缩多余空行
  let content = raw.replace(/\r\n/g, '\n').trim()
  content = content.replace(/\n{3,}/g, '\n\n')
  // 章节内容最多保留 12000 字符，避免单章过大
  if (content.length > 12000) {
    content = content.slice(0, 12000)
  }
  return {
    index: i + 1,
    title: p.title,
    content
  }
})

const out = {
  course: {
    title: '稀土掘金社区产品功能深度剖析',
    subtitle: '精选12篇干货，从沸点、圈子、话题到文章详情与推荐机制，一次读懂社区产品设计',
    description: '本课程精选12篇稀土掘金（Juejin）社区产品功能深度分析文章，涵盖沸点功能与详情页交互、沸点圈子与分类体系、话题功能前后端实现、文章列表与详情结构、推荐服务配额设计、掘友等级与掘力值体系、创作话题与活动等，帮助开发者系统理解社区产品设计与工程实现。',
    categoryId: 6,
    price: 0,
    originalPrice: 0
  },
  chapters
}

fs.writeFileSync(path.join(__dirname, 'course_chapters.json'), JSON.stringify(out, null, 2), 'utf8')
console.log('OK chapters=' + chapters.length + ' totalChars=' + chapters.reduce((s, c) => s + c.content.length, 0))
for (const c of chapters) {
  console.log(c.index + '. ' + c.title + ' (' + c.content.length + ' chars)')
}
