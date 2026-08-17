// ============  创作中心路由MODEL  ==================

/**
 * 创作者中心登录鉴权守卫
 * 拦截未登录用户访问 /creator 开头的页面，重定向到首页
 */
export function creatorGuard(to, from, next) {
  if (to.path.startsWith('/creator') || to.path.startsWith('/booklet')) {
    const token = localStorage.getItem('ACCESS_TOKEN')
    if (!token) {
      next('/home')
      return
    }
  }
  next()
}

// ===== 小册（独立顶层路由：全屏编辑器/规则页/申请页/管理维护子页，不嵌套 CreatorLayout） =====
let bookletRoutes = [
    {
        path: '/booklet/rules',
        name: 'BookletRules',
        component: () => import('@/pages/booklet/rules.vue')
    },
    {
        path: '/booklet/apply',
        name: 'BookletApply',
        component: () => import('@/pages/booklet/apply.vue')
    },
    {
        path: '/booklet/manage',
        name: 'BookletManage',
        component: () => import('@/pages/booklet/manage.vue')
    },
    {
        path: '/booklet/edit',
        name: 'BookletEdit',
        component: () => import('@/pages/creator/booklet/edit.vue')
    },
    {
        path: '/booklet/review/apply',
        name: 'BookletApplyReview',
        component: () => import('@/pages/creator/booklet/review/ApplyReview.vue')
    },
    {
        path: '/booklet/review/publish',
        name: 'BookletPublishReview',
        component: () => import('@/pages/creator/booklet/review/PublishReview.vue')
    }
]

let routes = [
    {
        path: '/creator',
        component: () => import('@/pages/creator/layout/CreatorLayout.vue'),
        redirect: '/creator/dashboard',
        children: [
            {
                path: 'dashboard',
                name: 'CreatorDashboard',
                component: () => import('@/pages/creator/dashboard/index.vue')
            },
            {
                path: 'publish',
                name: 'CreatorPublish',
                component: () => import('@/pages/creator/publish/index.vue')
            },
            {
                path: 'content',
                name: 'CreatorContent',
                component: () => import('@/pages/creator/content/index.vue')
            },
            {
                path: 'content/detail',
                name: 'CreatorContentDetail',
                component: () => import('@/pages/creator/content/detail.vue')
            },
            {
                path: 'article/list',
                name: 'CreatorArticleList',
                component: () => import('@/pages/creator/content/index.vue')
            },
            {
                path: 'column/list',
                name: 'CreatorColumnList',
                component: () => import('@/pages/creator/column/index.vue')
            },
            {
                path: 'pins/list',
                name: 'CreatorPinsList',
                component: () => import('@/pages/creator/pins/index.vue')
            },
            {
                path: 'course/list',
                name: 'CreatorCourseList',
                component: () => import('@/pages/creator/course/list.vue')
            },
            {
                path: 'course/edit',
                name: 'CreatorCourseEdit',
                component: () => import('@/pages/creator/course/edit.vue')
            },
            {
                path: 'course/discount',
                name: 'CreatorCourseDiscount',
                component: () => import('@/pages/creator/course/discount.vue')
            },
            {
                path: 'course/settlement',
                name: 'CreatorCourseSettlement',
                component: () => import('@/pages/creator/course/settlement.vue')
            },
            {
                // 小册站（一级栏目，作者自主运营小册母站）
                path: 'booklet',
                name: 'CreatorBooklet',
                component: () => import('@/pages/creator/booklet/index.vue')
            },
            {
                path: 'comment',
                name: 'CreatorComment',
                component: () => import('@/pages/creator/comment/index.vue')
            },
            {
                path: 'comment/detail',
                name: 'CreatorCommentDetail',
                component: () => import('@/pages/creator/comment/detail.vue')
            },
            {
                path: 'material',
                name: 'CreatorMaterial',
                component: () => import('@/pages/creator/material/material.vue')
            },
            {
                path: 'data',
                name: 'CreatorContentData',
                component: () => import('@/pages/creator/data/index.vue')
            },
            {
                path: 'fans',
                name: 'CreatorFans',
                component: () => import('@/pages/creator/fans/index.vue')
            },
            {
                path: 'fans/info',
                name: 'CreatorFansInfo',
                component: () => import('@/pages/creator/fans/info.vue')
            },
            {
                path: 'fans/list',
                name: 'CreatorFansList',
                component: () => import('@/pages/creator/fans/list.vue')
            },
            {
                path: 'user',
                name: 'CreatorUser',
                component: () => import('@/pages/creator/user/index.vue')
            },
            {
                path: 'growth/grade',
                name: 'CreatorGrowthGrade',
                component: () => import('@/pages/creator/growth/grade.vue')
            },
            {
                path: 'growth/inspiration',
                name: 'CreatorGrowthInspiration',
                component: () => import('@/pages/creator/growth/inspiration.vue')
            },
            {
                path: 'growth/topic/:id',
                name: 'CreatorTopicDetail',
                component: () => import('@/pages/creator/growth/topic/detail.vue')
            },
            {
                // /creator 下的未知路径：在创作中心布局内显示 404 页
                path: '*',
                name: 'CreatorNotFound',
                component: () => import('@/pages/not_found/index')
            }
        ]
    }
]

export default routes;

// 小册独立路由（全屏编辑器 + 审核页，不嵌套在 CreatorLayout）
export { bookletRoutes };