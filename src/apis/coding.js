import request from '@/common/article_request'
import store from '@/stores/store'

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

/**
 * 能力测评（Coding 延展第二层 · Stage B）
 * 全部需登录；交卷幂等（重复提交返回同一成绩单）。
 */

/** 能力测评：开卷（冷却内被拒并提示下次可考时间；有进行中返回续答） */
export const startCodingAssessment = () => {
  return request.post('/api/v1/coding/assessment/start')
}

/** 能力测评：进行中的卷（已超时返回空，后端懒过期） */
export const getCurrentAssessment = () => {
  return request.get('/api/v1/coding/assessment/current')
}

/** 能力测评：交卷（入参 {assessmentId, answers:[{questionId, userAnswer:[0]}]}） */
export const submitAssessment = (data) => {
  return request.post('/api/v1/coding/assessment/submit', data)
}

/** 能力测评：最近一次成绩单（无记录返回空） */
export const getLatestAssessment = () => {
  return request.get('/api/v1/coding/assessment/latest')
}

/** 能力测评：历史列表（分页，含进行中/已过期状态） */
export const getAssessmentHistory = (params = {}) => {
  return request.get('/api/v1/coding/assessment/history', { params })
}

/**
 * 模拟面试（Coding 延展第三层 · Stage A）
 * 全部需登录。start（提纲生成）与 finish（报告生成）为同步 LLM 调用，超时放宽到 60s；
 * 轮次为 SSE 流式（fetch + reader 手动解析，与 ai.js 的 precheckArticleStream 同一写法）。
 */

/** 模拟面试：开面（有进行中直接续答；每日场次有上限；额度不足被拒） */
export const startInterview = (data) => {
  return request.post('/api/v1/coding/interview/start', data, { timeout: 60000 })
}

/**
 * 模拟面试：解析简历文件为文本（PDF / DOC / DOCX / TXT / MD）
 *
 * 服务端不保存简历：解析结果只回给前端，由前端回填到可编辑输入框后随开面请求提交。
 * 返回 { code, data: { text, chars, truncated } }；扫描件/图片版会返回"没有提取到文字"。
 */
export const parseInterviewResume = (file) => {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/api/v1/coding/interview/resume/parse',
    method: 'post',
    data: formData,
    timeout: 60000
  })
}

/** 模拟面试：进行中的面试（无则返回空；已超时后端懒过期） */
export const getCurrentInterview = () => {
  return request.get('/api/v1/coding/interview/current')
}

/** 模拟面试：结束并生成报告（幂等；reportReady=false 时重新调用可重试生成） */
export const finishInterview = (data) => {
  return request.post('/api/v1/coding/interview/finish', data, { timeout: 60000 })
}

/** 模拟面试：报告 + 全量回放（仅本人） */
export const getInterviewReport = (id) => {
  return request.get('/api/v1/coding/interview/report', { params: { id } })
}

/** 模拟面试：已完成场次历史（分页，按开面时间倒序） */
export const getInterviewHistory = (params = {}) => {
  return request.get('/api/v1/coding/interview/history', { params })
}

/**
 * 模拟面试：提交作答（SSE 流式）
 *
 * 事件契约（后端 CodingInterviewController /turn）：
 * - event: delta  面试官发言增量（打字机效果；多行 data 以 '\n' join）
 * - event: done   JSON {text, kind: followup|next, topicIndex, turnCount, completed}
 * - event: error  业务错误文本；额度耗尽为 "[3301]…" 前缀（前端引导充值）
 *
 * @param {Object} payload { interviewId, answer, turnSeq }
 * @param {Object} handlers { onDelta(text), onDone(vo), onError(text) }
 * @returns {Function} abort() 调用可中途取消本次流式请求
 */
export const interviewTurnStream = (payload, handlers) => {
  const token = (store && store.state && store.state.accessToken) || ''
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null

  fetch('/content/api/v1/coding/interview/turn', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
      'accToken': token
    },
    body: JSON.stringify({
      interviewId: payload.interviewId,
      answer: payload.answer,
      turnSeq: payload.turnSeq
    }),
    signal: controller ? controller.signal : undefined
  }).then((resp) => {
    if (!resp.ok) {
      throw new Error('HTTP ' + resp.status)
    }
    if (!resp.body || !resp.body.getReader) {
      throw new Error('当前浏览器不支持流式响应')
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''                  // 累积行缓冲，处理 data 被 chunk 拆分
    let currentEvent = null       // { name, data:[] } 待分发的事件

    // 处理单行：event:/data: 行写入 currentEvent；空行触发事件分发
    function handleLine(line) {
      if (line === '') {
        dispatchEvent()
        return
      }
      if (line.startsWith('event:')) {
        if (!currentEvent) currentEvent = { name: null, data: [] }
        currentEvent.name = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        if (!currentEvent) currentEvent = { name: null, data: [] }
        currentEvent.data.push(line.slice(5).trim())
      }
    }

    // 分发一条完整 SSE 事件
    function dispatchEvent() {
      if (!currentEvent) return
      const evtName = currentEvent.name || ''
      const payloadText = currentEvent.data.join('\n')
      currentEvent = null
      if (payloadText === '') return
      if (evtName === 'delta') {
        if (handlers && handlers.onDelta) handlers.onDelta(payloadText)
      } else if (evtName === 'done') {
        let vo = {}
        try { vo = JSON.parse(payloadText) } catch (e) { vo = {} }
        if (handlers && handlers.onDone) handlers.onDone(vo)
      } else if (evtName === 'error') {
        if (handlers && handlers.onError) handlers.onError(payloadText)
      }
    }

    // 逐块读取并切分出行，交给 handleLine
    function pump() {
      return reader.read().then((res) => {
        if (res.done) {
          // 流结束：处理残留未换行的数据，并触发最后一次事件分发
          if (buf) {
            handleLine(buf)
            buf = ''
          }
          handleLine('')
          return
        }
        buf += decoder.decode(res.value, { stream: true })
        let idx
        while ((idx = buf.indexOf('\n')) !== -1) {
          const line = buf.slice(0, idx)
          buf = buf.slice(idx + 1)
          handleLine(line)
        }
        return pump()
      })
    }
    return pump()
  }).catch((err) => {
    // fetch 异常/取消（AbortError）等一律交给 onError 兜底展示
    if (handlers && handlers.onError) {
      handlers.onError((err && err.message) || '网络错误，面试进行失败')
    }
  })

  // 返回 abort() 用于中途取消（组件销毁时调用）
  return function abort() {
    if (controller) controller.abort()
  }
}