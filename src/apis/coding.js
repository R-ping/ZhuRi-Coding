import request from '@/common/article_request'

/**
 * 每日一题与刷题接口（Coding 延展第一层）
 * 榜单与题库列表公开只读；今日题/作答/统计/出题需登录。
 */

/** 今日题目（difficulty 可选：1入门 2进阶 3挑战，缺省按历史正确率自适应） */
export const getTodayQuestion = (difficulty) => {
  return request.get('/api/v1/coding/today', { params: difficulty ? { difficulty } : {} })
}

/** 提交作答（isDaily=true 计"当日一题"，false 为自由练习） */
export const submitCodingAnswer = (data) => {
  return request.post('/api/v1/coding/answer', data)
}

/** 榜单（period：day 当日 / week 本周 / month 本月） */
export const getCodingRanking = (period = 'day') => {
  return request.get('/api/v1/coding/ranking', { params: { period } })
}

/** 题库列表（练习，不含答案；登录时标记已答） */
export const getCodingQuestions = (params = {}) => {
  return request.get('/api/v1/coding/questions', { params })
}

/** 我的编码统计（连续天数/正确率/领域分布） */
export const getCodingStat = () => {
  return request.get('/api/v1/coding/stat')
}

/** AI 从自己的文章生成题目（每日有次数上限） */
export const generateQuestionsFromArticle = (articleId) => {
  return request.post('/api/v1/coding/question/generate', { articleId })
}

/** 作者投稿题目：格式校验 + 查重 + AI 质检 */
export const submitCodingQuestion = (data) => {
  return request.post('/api/v1/coding/question/submit', data)
}

/**
 * 能力档案（Coding 延展第二层）
 * 本人档案与隐私开关需登录；公开档案匿名可访问（挂个人主页白名单前缀）。
 */

/** 能力档案：本人完整档案（需登录） */
export const getMyAbilityProfile = () => {
  return request.get('/api/v1/coding/profile/me')
}

/** 能力档案：公开档案（他人主页/分享页；登录且为本人时返回全量） */
export const getUserAbilityProfile = (userId) => {
  return request.get(`/api/v1/user/home/${userId}/ability`)
}

/** 能力档案隐私开关：读取（需登录，无记录返回默认值） */
export const getAbilitySetting = () => {
  return request.get('/api/v1/coding/profile/setting')
}

/** 能力档案隐私开关：保存（需登录，字段为空表示不修改） */
export const updateAbilitySetting = (data) => {
  return request.put('/api/v1/coding/profile/setting', data)
}