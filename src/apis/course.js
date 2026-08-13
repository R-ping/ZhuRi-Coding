import request from '@/common/request'
import conf from '@/common/conf'

const API_PREFIX = '/content/api/v1/course'
const COURSE_API_PREFIX = '/content/api/v1/course'

export default {
  // ========== 课程创作管理 ==========

  /** 检查课程创作权限 */
  checkAuthorPermission() {
    return request.get(`${API_PREFIX}/author/check-permission`, {})
  },

  /** 创建课程草稿 */
  createCourse(data) {
    return request.post(`${API_PREFIX}/manage/create`, data)
  },

  /** 更新课程信息 */
  updateCourse(data) {
    return request.put(`${API_PREFIX}/manage/update`, data)
  },

  /** 作者课程管理列表 */
  getManageList(params) {
    return request.get(`${API_PREFIX}/manage/list`, params)
  },

  /** 课程编辑详情（含所有章节） */
  getManageDetail(params) {
    return request.get(`${API_PREFIX}/manage/detail`, params)
  },

  /** 软删除课程 */
  deleteCourse(data) {
    return request.post(`${API_PREFIX}/manage/delete`, data)
  },

  /** 提交审核 */
  submitForReview(data) {
    return request.post(`${API_PREFIX}/manage/submit`, data)
  },

  /** 下架课程 */
  unpublishCourse(data) {
    return request.post(`${API_PREFIX}/manage/unpublish`, data)
  },

  // ========== 章节管理 ==========

  /** 创建章节 */
  createChapter(data) {
    return request.post(`${API_PREFIX}/chapter/create`, data)
  },

  /** 更新章节 */
  updateChapter(data) {
    return request.put(`${API_PREFIX}/chapter/update`, data)
  },

  /** 删除章节 */
  deleteChapter(id) {
    return request.delete(`${API_PREFIX}/chapter/${id}`, {})
  },

  /** 批量更新章节排序 */
  updateChapterSort(data) {
    return request.put(`${API_PREFIX}/chapter/sort`, data)
  },

  // ========== 公开课程 ==========

  /** 课程列表 */
  getCourseList(params) {
    return request.get(`${API_PREFIX}/list`, params)
  },

  /** 公开课程详情（含章节列表） */
  getCourseDetail(params) {
    return request.get(`${API_PREFIX}/detail`, params)
  },

  /** 公开章节详情（阅读用） */
  getChapterDetail(id) {
    return request.get(`${API_PREFIX}/chapter/${id}/detail`, {})
  },

  /** 我的课程 */
  getMyCourses(params) {
    return request.get(`${API_PREFIX}/my`, params)
  },

  /** 更新阅读进度 */
  updateProgress(data) {
    return request.post(`${API_PREFIX}/progress`, data)
  },

  // ========== 订单管理 (course微服务) ==========

  /** 创建订单 */
  createOrder(data) {
    return request.post(`${COURSE_API_PREFIX}/order/create`, data)
  },

  /** 查询订单状态 */
  getOrderStatus(orderNo) {
    return request.get(`${COURSE_API_PREFIX}/order/status`, { orderNo })
  },

  /** 我的订单列表 */
  getMyOrders(params) {
    return request.get(`${COURSE_API_PREFIX}/order/my`, params)
  },

  // ========== 支付 (course微服务) ==========

  /** 获取支付页面URL */
  getPayPageUrl(orderNo) {
    return `${COURSE_API_PREFIX}/pay/page?orderNo=${orderNo}`
  },

  // ========== 折扣码管理 (course微服务) ==========

  /** 创建折扣码 */
  createDiscount(data) {
    return request.post(`${COURSE_API_PREFIX}/discount/create`, data)
  },

  /** 折扣码列表 */
  getDiscountList(params) {
    return request.get(`${COURSE_API_PREFIX}/discount/list`, params)
  },

  /** 停用折扣码 */
  disableDiscount(data) {
    return request.post(`${COURSE_API_PREFIX}/discount/disable`, data)
  },

  /** 校验折扣码（下单前预览） */
  validateDiscount(params) {
    return request.get(`${COURSE_API_PREFIX}/discount/validate`, params)
  },

  // ========== 收入结算 (course微服务) ==========

  /** 月度结算列表 */
  getSettlementMonthly() {
    return request.get(`${COURSE_API_PREFIX}/settlement/monthly`, {})
  },

  /** 结算明细 */
  getSettlementDetail(settlementId) {
    return request.get(`${COURSE_API_PREFIX}/settlement/detail`, { settlementId })
  },

  /** 作者打赏收益汇总 */
  getTipRevenue() {
    return request.get('/content/api/v1/tip/my-revenue', {})
  }
}