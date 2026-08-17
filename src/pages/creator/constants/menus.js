export const MenuData = [
  { title: '首页', path: '/creator/dashboard', icon: 'el-icon-s-home' },
  {
    title: '内容管理',
    icon: 'el-icon-document',
    children: [
      { title: '文章管理', path: '/creator/article/list' },
      { title: '评论管理', path: '/creator/comment' },
      { title: '专栏管理', path: '/creator/column/list' },
      { title: '沸点管理', path: '/creator/pins/list' }
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
  },
  {
    // 小册站：一级栏目置于最末，样式与其它栏目区分（见 SidebarItem/Sidebar 样式高亮）
    title: '小册站',
    path: '/creator/booklet',
    icon: 'el-icon-notebook-2',
    bookletStation: true
  }
  ]
