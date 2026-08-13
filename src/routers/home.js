// ============  主页路由MODEL  ==================
// 布局组件（app shell）保持静态导入，避免首屏闪烁
import Layout from '@/components/layouts/layout_main'
// 路由组件全部使用动态导入，实现按需加载
const Home = () => import('@/pages/home/index')
const Search = () => import('@/pages/search/index')
const Screen = () => import('@/pages/load_screen/index')
const SearchResult = () => import('@/pages/search_result/index')
const OAuthCallback = () => import('@/pages/oauth_callback/index')
const User = () => import('@/pages/user/index')
const UserSettings = () => import('@/pages/user/settings/index')
const UserGrowth = () => import('@/pages/user/growth/index')
const UserGrowthJScore = () => import('@/pages/user/growth/jscore')
const UserCheckin = () => import('@/pages/user/checkin/index')
const UserLottery = () => import('@/pages/user/lottery/index')
const UserWelfare = () => import('@/pages/user/welfare/index')
const UserWelfareRedeem = () => import('@/pages/user/welfare/redeem')
const UserCourses = () => import('@/pages/user/courses/index')
const UserHistory = () => import('@/pages/user/history/index')
const Notification = () => import('@/pages/notification/index')
const Pins = () => import('@/pages/pins/index.vue')
const PinsCircles = () => import('@/pages/pins/circles.vue')
const PinsDetail = () => import('@/pages/pins/detail.vue')
const Course = () => import('@/pages/course/index.vue')
const CourseDetail = () => import('@/pages/course/detail.vue')
const CourseRead = () => import('@/pages/course/read.vue')
const CourseOrder = () => import('@/pages/course/order.vue')
const Hot = () => import('@/pages/hot/index.vue')
const TopicSquare = () => import('@/pages/pin/topics/index.vue')
const TopicDetail = () => import('@/pages/pin/topic/detail.vue')
const CircleDetail = () => import('@/pages/pins/circle/detail.vue')

let routes = [
    {
        path: '/',
        component: Layout,
        children:[
            {
                path:'/home',
                name:'Home',
                component: Home
            },
            {
                path:'/home/:category',
                name:'HomeCategory',
                component: Home
            },
            {
                path:'/hot',
                name:'hot',
                component: Hot
            },
            {
                path:'/user/settings',
                name:'user-settings',
                component:UserSettings
            },
            {
                path:'/user/center/growth',
                name:'user-center-growth',
                component:UserGrowth
            },
            {
                path:'/user/center/growth/jscore',
                name:'user-center-growth-jscore',
                component:UserGrowthJScore
            },
            {
                path:'/user/center/checkin',
                name:'user-center-checkin',
                component:UserCheckin
            },
            {
                path:'/user/center/lottery',
                name:'user-center-lottery',
                component:UserLottery
            },
            {
                path:'/user/center/welfare',
                name:'user-center-welfare',
                component:UserWelfare
            },
            {
                path:'/user/center/welfare/redeem/:id',
                name:'user-center-welfare-redeem',
                component:UserWelfareRedeem
            },
            {
                path:'/user/courses',
                name:'user-courses',
                component:UserCourses
            },
            {
                path:'/user/history',
                name:'user-history',
                component:UserHistory
            },
            {
                path:'/user/:id',
                name:'user-profile',
                component:User
            },
            {
                path:'/notification',
                name:'notification',
                component:Notification
            },
            {
                path:'/pins',
                name:'pins',
                component:Pins
            },
            {
                path:'/pins/detail/:id',
                name:'pins-detail',
                component:PinsDetail
            },
            {
                path:'/pins/circles',
                name:'pins-circles',
                component:PinsCircles
            },
            {
                path:'/pin/topics',
                name:'pin-topics',
                component:TopicSquare
            },
            {
                path:'/pin/topic/:id',
                name:'pin-topic-detail',
                component:TopicDetail
            },
            {
                path:'/pins/circle/:id',
                name:'pins-circle-detail',
                component:CircleDetail
            },
            {
                path:'/course',
                name:'course',
                component:Course
            },
            {
                path:'/course/order/:orderNo',
                name:'course-order',
                component:CourseOrder
            },
            {
                path:'/course/:id',
                name:'course-detail',
                component:CourseDetail
            },
            {
                path:'/course/read/:id',
                name:'course-read',
                component:CourseRead
            }
        ]
    },{
        path:'/search_result',
        component: Layout,
        children: [{
            path:'',
            name:'search_result',
            component: SearchResult,
            props:true
        }]
    },{
        path:'/screen',
        name: 'screen',
        component:Screen
    },{
        path:'/search',
        name:'search',
        component:Search
    },{
        path:'/oauth/callback',
        name:'oauth-callback',
        component:OAuthCallback
    }
]

export default routes;