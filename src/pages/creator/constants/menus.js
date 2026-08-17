export const MenuData = [
  { title: '首页', path: '/creator/dashboard', icon: 'el-icon-s-home' },
  {
    title: '内容管理',
    icon: 'el-icon-document',
    children: [
      { title: '文章管理', path: '/creator/article/list' },
      { title: '评论管理', path: '/creator/comment' },
      { title: '专栏管理', path: '/creator/column/list' },
      { title: '沸点管理', path: '/creator/pins/list' },
      {
        title: '课程管理',
        path: '/creator/course/list',
        requiredPermission: 'can_create_course',
        permissionTip: '需要逐力值Lv9解锁'
      }
    ]
  },
  {
    title: '课程运营',
    icon: 'el-icon-s-marketing',
    requiredPermission: 'can_create_course',
    permissionTip: '需要逐力值Lv9解锁',
    children: [
      { title: '折扣码管理', path: '/creator/course/discount' },
      { title: '收入结算', path: '/creator/course/settlement' }
    ]
  },
  {
    title: '数据中心',
    icon: 'el-icon-s-data',
    children: [
      { title: '内容数据', path: '/creator/data' },
      { title: '粉丝数据', path: '/creator/fans' }
    ]
  },
  {
    title: '创作成长',
    icon: 'el-icon-s-promotion',
    children: [
      { title: '创作等级权益', path: '/creator/growth/grade' },
      { title: '创作任务', path: '/creator/growth/tasks' },
      { title: '创作灵感', path: '/creator/growth/inspiration' }
    ]
  },
  {
    // 小册审核：仅编辑白名单账号可见（Sidebar 按 editorOnly 过滤）
    title: '小册审核',
    icon: 'el-icon-s-check',
    editorOnly: true,
    children: [
      { title: '申报审核', path: '/booklet/review/apply' },
      { title: '上架审核', path: '/booklet/review/publish' }
    ]
  }
  ]
