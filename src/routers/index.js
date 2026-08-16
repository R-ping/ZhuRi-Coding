import Home from './home'
import Creator, { bookletRoutes } from './creator'

let routes = []

let concat = (router) => {
    routes = routes.concat(router)
}
// 合并'主页'相关路由
routes = routes.concat(Home)
// 合并'创作中心'相关路由
routes = routes.concat(Creator)
// 合并'小册'独立路由（全屏编辑器 + 审核页）
routes = routes.concat(bookletRoutes)
// 兜底：未知路径跳转 404 页（vue-router 3.x 使用 '*' 通配）
routes = routes.concat([{
    path: '*',
    name: 'not-found',
    component: () => import('@/pages/not_found/index')
}])

export default  routes;
