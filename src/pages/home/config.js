export default {
    // 频道列表：综合频道（含 推荐/最新/关注 分栏）+ 8个分类频道
    // 频道ID与数据库 ap_channel 表一致
    tabTitles: [
        {title: '综合', id:'__all__'},
        {title: '后端',id:1},
        {title: '前端',id:2},
        {title: 'Android',id:3},
        {title: 'iOS',id:4},
        {title: '人工智能',id:5},
        {title: '开发工具',id:6},
        {title: '代码人生',id:7},
        {title: '阅读',id:8}
    ],
    // 综合频道分栏：推荐/最新/关注（关注走 recommend_follow 分流接口）
    comprehensiveSubTabs: [
        {key: 'recommend', title: '推荐'},
        {key: 'latest', title: '最新'},
        {key: 'follow', title: '关注'}
    ],
    // 分类频道分栏：推荐/最新（通过 recommend_cate 的 subTab 参数区分）
    categorySubTabs: [
        {key: 'recommend', title: '推荐'},
        {key: 'latest', title: '最新'}
    ],
    tabStyles: {
        bgColor: '#FFFFFF',
        titleColor: '#9b9b9b',
        activeTitleColor: '#3D3D3D',
        activeBgColor: '#FFFFFF',
        isActiveTitleBold: true,
        iconWidth: 70,
        iconHeight: 70,
        width: 120,
        height: 80,
        fontSize: 24,
        hasActiveBottom: true,
        activeBottomColor: '#3194ff',
        activeBottomHeight: 6,
        activeBottomWidth: 36,
        textPaddingLeft: 10,
        textPaddingRight: 10,
        normalBottomColor: 'rgba(0,0,0,0.4)',
        normalBottomHeight: 2,
        hasRightIcon: false,
        rightOffset: 100
    }
}
