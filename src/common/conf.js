const  config = {
    // 注册对应服务名称
    services:{
        content:'CONTENT',
        user:'USER',
        search:'SEARCH',
        notification:'NOTIFICATION'
    },
    // 请求本地的请求service
    local:{user:true,content:true,search:true,notification:true},
    // 代理前缀
    prefix:{
        server_85:'/server_85'
    },
    urls:{
        recommend:{url:'api/v1/article/recommend',sv:'content'},
        // ==========  notification (站内信)
        notifications_list:{url:'api/v1/notifications',sv:'notification'},
        notifications_unread:{url:'api/v1/notifications/unread-count',sv:'notification'},
        notifications_mark_read:{url:'api/v1/notifications/mark-all-read',sv:'notification'},
        notifications_mark_type_read:{url:'api/v1/notifications/mark-type-read',sv:'notification'},
        notifications_reply:{url:'api/v1/notifications/actions/reply',sv:'notification'},
        notifications_like:{url:'api/v1/notifications/actions/like',sv:'notification'},
        notifications_follow_back:{url:'api/v1/notifications/actions/follow-back',sv:'notification'},
        im_sessions:{url:'api/v1/im/sessions',sv:'notification'},
        im_session:{url:'api/v1/im/session',sv:'notification'},
        im_messages:{url:'api/v1/im/messages',sv:'notification'},
        im_send:{url:'api/v1/im/messages',sv:'notification'},
        im_read:{url:'api/v1/im/messages/read',sv:'notification'},
        // ==========  content (原 article 服务，已合并)
        load:{url:'api/v1/article/load/',sv:'content'},
        loadmore:{url:'api/v1/article/load/more',sv:'content'},
        loadnew:{url:'api/v1/article/load/new',sv:'content'},
        article_info:{url:'api/v1/article/info',sv:'content'},
        article_content:{url:'api/v1/article/content',sv:'content'},
        // ==========  comment (content服务)
        comment_list:{url:'api/v1/comment/list',sv:'content'},
        comment_add:{url:'api/v1/comment',sv:'content'},
        comment_like:{url:'api/v1/comment/like',sv:'content'},
        // ==========  search (后端已实现)
        load_search_history:{url:'api/v1/history/load',sv:'search'},
        del_search:{url:'api/v1/history/del',sv:'search'},
        clear_search:{url:'api/v1/history/clear',sv:'search'},
        associate_search:{url:'api/v1/associate/search',sv:'search'},
        // 统一搜索：单端点 /api/v1/search（sv=search），分栏由 id_type 控制；文章走本服务 ES，课程/标签/用户经 Feign 聚合
        unified_search:{url:'api/v1/search',sv:'search'},
        // 后端未提供 load_hot_keywords 接口，已在前端注释对应调用
        // ==========  behavior (已合并入 content 服务，统一走事件总线入口 /api/v1/behavior/*)
        read_behavior:{url:'api/v1/behavior/browse',sv:'content'},
        like_behavior:{url:'api/v1/behavior/like',sv:'content'},
        unlike_behavior:{url:'api/v1/behavior/unlike',sv:'content'},
        collection_behavior:{url:'api/v1/behavior/collect',sv:'content'},
        uncollect_behavior:{url:'api/v1/behavior/uncollect',sv:'content'},
        // 关注/取关统一走 /api/v1/follow/do（见 src/apis/follow.js），不再走行为总线配置
        // ==========  user (后端已实现)
        // 后端未提供 user_follow 接口，已在前端注释对应调用
        // ==========  login (login 属于 user 微服务)
        user_login:{url:'api/v1/login/login_auth',sv:'user'},
        user_code:{url:'api/v1/login/code',sv:'user'},
        user_social_bind:{url:'api/v1/login/social_bind',sv:'user'},
        user_token_refresh:{url:'api/v1/token/refresh',sv:'user'},
        oauth_github:{url:'oauth2/code/github',sv:'user'},
        oauth_weibo:{url:'oauth2/code/weibo',sv:'user'},
        // 后端未提供 wechat_login 接口，已在前端注释对应调用
        // 解决多访问地址的问题
        getBase : function(url){
            let sv = url.sv
            // 默认指向85服务器，并指向网关+服务名；否则走本地，不加服务名
            if(config.local[sv]){
                return "/"+sv;
            }else{
                return config.prefix.server_85+'/'+config.services[sv];
            }
        },
        get:function(name){
            let tmp = config.urls[name];
            if(tmp)
                return config.urls.getBase(tmp)+"/"+tmp.url;
            else
                return name;
        }
    },
    style : {
        main_bg : '#3296fa'
    },
    noAction:function(){
        var msg = '该功能暂未实现';
        console.warn(msg);
        // 使用轻量级 toast 替代 alert，避免阻塞 UI
        var el = document.createElement('div');
        el.textContent = msg;
        el.style.cssText = 'position:fixed;top:20px;left:50%;transform:translateX(-50%);background:#333;color:#fff;padding:10px 24px;border-radius:6px;z-index:99999;font-size:14px;transition:opacity .3s';
        document.body.appendChild(el);
        setTimeout(function(){ el.style.opacity='0'; setTimeout(function(){ el.remove(); },300); },2000);
    }

}
export default config