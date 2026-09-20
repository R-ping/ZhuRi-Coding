// 临时脚本：重建 ES app_info_article 索引
// 背景：此前 ad-hoc 回填曾用 JS Number 解析 19 位雪花 ID，导致 ES 中 _id 精度丢失（如 DB
//       2086403442600767490 -> ES 2086403442600767500），搜索结果点击文章 404。
// 修复：从 MySQL 以字符串导出真实文章 ID（CAST AS CHAR），清空索引后按精确字符串 _id 重建。
const { execFileSync } = require('child_process')

const MYSQL_HOST = process.env.DB_HOST || 'localhost'
const MYSQL_USER = process.env.DB_USER || 'root'
const MYSQL_PASS = process.env.DB_PASSWORD || '123456'
const DB = 'leadnews_article'

const ES_URL = process.env.ES_URL || 'http://localhost:9201'
const ES_USER = process.env.ES_USERNAME || 'elastic'
const ES_PASS = process.env.ES_PASSWORD || 'x8YMlQeWvd4IGPnrb-4k'
const INDEX = process.env.ES_ARTICLE_INDEX || 'app_info_article'

const auth = 'Basic ' + Buffer.from(ES_USER + ':' + ES_PASS).toString('base64')

async function mit(method, url, body) {
  const res = await fetch(ES_URL + url, {
    method,
    headers: { Authorization: auth, 'Content-Type': 'application/json' },
    body: body == null ? undefined : (typeof body === 'string' ? body : JSON.stringify(body))
  })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(method + ' ' + url + ' -> ' + res.status + ' ' + text)
  }
  return res.json()
}

async function main() {
  // 1. 从 MySQL 导出已发布文章（bigint 均 CAST AS CHAR，保证 19 位精确）
  const sql =
    "SELECT JSON_OBJECT(" +
      "'id', CAST(a.id AS CHAR), " +
      "'title', IFNULL(a.title,''), " +
      "'layout', IFNULL(a.layout,0), " +
      "'authorId', CAST(IFNULL(a.author_id,0) AS CHAR), " +
      "'authorName', IFNULL(a.author_name,''), " +
      "'publishTime', IFNULL(UNIX_TIMESTAMP(a.publish_time)*1000,0), " +
      "'images', IFNULL(a.cover_image,''), " +
      "'staticUrl', IFNULL(a.static_url,''), " +
      "'content', IFNULL((SELECT d.content FROM ap_article_draft d WHERE d.article_id=a.id ORDER BY d.id DESC LIMIT 1),''), " +
      "'status', IFNULL(a.status,0)" +
    ") FROM ap_article a WHERE a.status=9 AND a.is_deleted=0 ORDER BY a.id"
  const out = execFileSync(
    'mysql',
    ['-h', MYSQL_HOST, '-u', MYSQL_USER, '-p' + MYSQL_PASS, '--default-character-set=utf8mb4', '-N', '--batch', '--raw', '-D', DB, '-e', sql],
    { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 }
  )

  const rows = out.split(/\r?\n/).filter(Boolean).map((l) => {
    // 去掉每行首尾的 Tab（批处理/raw 下 JSON 与行首可能有分隔符）
    const line = l.replace(/^\s+/, '').replace(/\s+$/, '')
    return JSON.parse(line)
  })
  console.log('DB 已发布文章数 =', rows.length)

  // 2. 清空现有索引（保留映射），避免残留精度丢失文档
  const del = await mit('POST', '/' + INDEX + '/_delete_by_query?refresh=true', { query: { match_all: {} } })
  console.log('已清空旧索引，删除文档数 =', del.deleted)

  // 3. 批量重建（_id 用精确字符串；id 字段也以字符串提交，让 ES 按 long 精确存储）
  const body = []
  for (const r of rows) {
    // 移除图片 URL 上的查询参数（?: 及之后）——领目规范要求
    const images = String(r.images || '').split('?')[0]
    const source = {
      _class: 'com.zhuri.coding.search.entity.SearchArticle',
      id: r.id, // 字符串，ES long 字段会精确强转
      title: r.title,
      layout: Number(r.layout) || 0,
      authorId: r.authorId, // 字符串强转 long
      authorName: r.authorName,
      publishTime: Number(r.publishTime) || 0,
      images,
      staticUrl: r.staticUrl || '',
      fileName: '',
      content: r.content || '',
      htmlContent: r.content || '',
      tocList: [],
      authorWorks: [],
      status: Number(r.status) || 0
    }
    body.push(JSON.stringify({ index: { _index: INDEX, _id: r.id } }))
    body.push(JSON.stringify(source))
  }
  body.push('')

  const bulk = await mit('POST', '/_bulk?refresh=true', body.join('\n'))
  // 统计
  const items = bulk.items || []
  const ok = items.filter((it) => it.index && it.index.status && it.index.status < 300).length
  const err = items.filter((it) => it.index && it.index.status && it.index.status >= 300)
  console.log('BULK 成功 =', ok, '/', items.length)
  if (err.length) {
    console.log('失败示例:', JSON.stringify(err.slice(0, 3)))
  }
}

main().catch((e) => {
  console.error('FATAL', e)
  process.exit(1)
})