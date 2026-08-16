import store from '@/stores/store'

const PERMISSIONS = {
  canSendPrivateMessage: 'can_send_private_message',
  canSetCommentPermission: 'can_set_comment_permission',
  canCreatePoll: 'can_create_poll',
  canBecomeContributor: 'can_become_contributor',
  canBeRecommended: 'can_be_recommended',
  canAddVideo: 'can_add_video',
  canAdd2Tags: 'can_add_2_tags',
  canSchedulePublish: 'can_schedule_publish',
  canAdd3Tags: 'can_add_3_tags',
  canAdd4Tags: 'can_add_4_tags',
  canCreateCourse: 'can_create_course'
}

export const permission = {
  PERMISSIONS,

  // ===== 小册编辑白名单（与后端 EditorConfig.EDITOR_USER_IDS 保持一致） =====
  EDITOR_USER_IDS: [4],

  /**
   * 判断当前登录用户是否为小册编辑（账号白名单身份）
   * 编辑负责：申报审核、上架审核、发布小节、下架
   */
  isEditor() {
    const user = store.getters.userInfo
    if (!user || !user.userId) return false
    return this.EDITOR_USER_IDS.includes(Number(user.userId))
  },

  hasPermission(permissionCode) {
    const user = store.getters.getUserInfo
    if (!user) return false

    const permissions = user.permissions || []
    return permissions.includes(permissionCode)
  },

  getMaxTags() {
    if (this.hasPermission(PERMISSIONS.canAdd4Tags)) return 4
    if (this.hasPermission(PERMISSIONS.canAdd3Tags)) return 3
    if (this.hasPermission(PERMISSIONS.canAdd2Tags)) return 2
    return 1
  },

  canAddVideo() {
    return this.hasPermission(PERMISSIONS.canAddVideo)
  },

  canSchedulePublish() {
    return this.hasPermission(PERMISSIONS.canSchedulePublish)
  },

  async canCreateCourse() {
    try {
      const courseApi = (await import('@/apis/course')).default
      const res = await courseApi.checkAuthorPermission()
      if (res && res.code === 200 && res.data) {
        return {
          hasPermission: res.data.hasPermission,
          powerLevel: res.data.powerLevel,
          requiredLevel: res.data.requiredLevel
        }
      }
    } catch (e) {
      console.error('检查课程权限失败', e)
    }
    return { hasPermission: false, powerLevel: 0, requiredLevel: 9 }
  },

  canSendPrivateMessage() {
    return this.hasPermission(PERMISSIONS.canSendPrivateMessage)
  },

  canSetCommentPermission() {
    return this.hasPermission(PERMISSIONS.canSetCommentPermission)
  },

  async checkPermissionFromServer(userId, permissionCode) {
    try {
      const response = await fetch(`/api/v1/level/user/${userId}/permission/${permissionCode}`)
      const data = await response.json()
      return data.hasPermission || false
    } catch (error) {
      console.error('检查权限失败:', error)
      return false
    }
  },

  async getUserPermissions(userId) {
    try {
      const response = await fetch(`/api/v1/level/user/${userId}/permissions`)
      const data = await response.json()
      return data || []
    } catch (error) {
      console.error('获取权限列表失败:', error)
      return []
    }
  }
}

export default permission
