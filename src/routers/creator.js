// ============  创作中心路由MODEL  ==================

/**
 * 创作者中心登录鉴权守卫
 * 拦截未登录用户访问 /creator 开头的页面，重定向到首页
 */
export function creatorGuard(to, from, next) {
  if (to.path.startsWith('/creator')) {
    const token = localStorage.getItem('ACCESS_TOKEN')
    if (!token) {
      next('/home')
      return
    }
  }
  next()
}

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
            }
        ]
    },
    {
        path: '*',
        name: 'CreatorNotFound',
        component: () => import('@/pages/creator/layout/CreatorLayout.vue')
    }
]

export default routes;