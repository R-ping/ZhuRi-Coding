<template>
    <div class="ability-page">
        <!-- 头部：标题 + 操作（分享 / 回到每日一题） -->
        <div class="ability-header">
            <div class="header-left">
                <h1 class="page-title">能力档案</h1>
                <span class="page-sub">把答题沉淀、内容输出与测评成绩，整理成可展示的能力证明</span>
            </div>
            <div class="header-right">
                <button class="ghost-btn" @click="copyShareLink">复制分享链接</button>
                <button class="ghost-btn" @click="goDaily">每日一题</button>
            </div>
        </div>

        <!-- 加载 / 失败 -->
        <div v-if="loading" class="card state-card">档案加载中...</div>
        <div v-else-if="loadError" class="card state-card">{{ loadError }}</div>

        <template v-else>
            <!-- 本人页未登录 -->
            <div v-if="needLogin" class="card state-card">
                <div class="state-icon">🔑</div>
                <div class="state-title">登录后查看你的能力档案</div>
                <div class="state-desc">档案记录你的答题领域、持续度与内容输出，默认仅自己可见</div>
                <button class="primary-btn" @click="showLogin">登录 / 注册</button>
            </div>

            <template v-else-if="profile">
                <!-- 访客视角：整体未公开 -->
                <div v-if="!isSelf && !profile.isPublic" class="card state-card">
                    <div class="state-icon">🔒</div>
                    <div class="state-title">该用户未公开能力档案</div>
                    <div class="state-desc">能力档案默认私有，仅用户本人可查看</div>
                </div>

                <template v-else>
                    <!-- 身份行 -->
                    <div class="card identity-card">
                        <img :src="profile.avatar || defaultAvatar" class="identity-avatar" alt="avatar">
                        <div class="identity-meta">
                            <div class="identity-name">{{ profile.nickname || '用户' }}</div>
                            <div class="identity-sub" v-if="isSelf">
                                这是你的能力档案 · {{ profile.isPublic ? '已公开，访客可通过分享链接查看' : '默认仅自己可见，可在下方开启公开' }}
                            </div>
                            <div class="identity-sub" v-else>TA 已公开的能力档案</div>
                        </div>
                    </div>

                    <!-- 能力板块：四块（"解决问题"依赖付费问答，未上线前不渲染） -->
                    <div class="blocks-grid">
                        <!-- 技术领域分布 -->
                        <div class="card block-card">
                            <div class="block-head">
                                <span class="block-title">技术领域分布</span>
                                <span class="block-badge" v-if="isPrivate(blocks.domain)">未公开</span>
                            </div>
                            <template v-if="!isPrivate(blocks.domain)">
                                <div v-if="blocks.domain && blocks.domain.available && domainItems.length" class="domain-list">
                                    <div v-for="item in domainItems" :key="item.tag" class="domain-item">
                                        <span class="domain-name">{{ item.tag }}</span>
                                        <div class="domain-bar">
                                            <div class="domain-bar-inner" :style="{ width: domainRate(item) + '%' }"></div>
                                        </div>
                                        <span class="domain-num">{{ item.correct || 0 }}/{{ item.total || 0 }}</span>
                                    </div>
                                </div>
                                <div v-else class="block-empty">暂无答题数据，去每日一题积累第一条记录</div>
                            </template>
                            <div v-else class="block-empty">该板块未公开</div>
                        </div>

                        <!-- 持续度 -->
                        <div class="card block-card">
                            <div class="block-head">
                                <span class="block-title">持续度</span>
                                <span class="block-badge" v-if="isPrivate(blocks.streak)">未公开</span>
                            </div>
                            <template v-if="!isPrivate(blocks.streak)">
                                <div v-if="blocks.streak && blocks.streak.available" class="streak-body">
                                    <div class="streak-nums">
                                        <div class="streak-cell">
                                            <span class="streak-value">{{ blocks.streak.continuousDays || 0 }}</span>
                                            <span class="streak-label">连续答题天数</span>
                                        </div>
                                        <div class="streak-cell">
                                            <span class="streak-value">{{ blocks.streak.activeMonths || 0 }}</span>
                                            <span class="streak-label">活跃月份</span>
                                        </div>
                                    </div>
                                    <div class="streak-dates" v-if="blocks.streak.firstAnswerDate">
                                        {{ blocks.streak.firstAnswerDate }} 起 · 最近 {{ blocks.streak.lastAnswerDate }}
                                    </div>
                                </div>
                                <div v-else class="block-empty">暂无持续记录，连续答题会沉淀在这里</div>
                            </template>
                            <div v-else class="block-empty">该板块未公开</div>
                        </div>

                        <!-- 输出能力 -->
                        <div class="card block-card">
                            <div class="block-head">
                                <span class="block-title">输出能力</span>
                                <span class="block-badge" v-if="isPrivate(blocks.output)">未公开</span>
                            </div>
                            <template v-if="!isPrivate(blocks.output)">
                                <div v-if="blocks.output && blocks.output.available" class="streak-body">
                                    <div class="streak-nums">
                                        <div class="streak-cell">
                                            <span class="streak-value">{{ blocks.output.articleCount || 0 }}</span>
                                            <span class="streak-label">已发布文章</span>
                                        </div>
                                        <div class="streak-cell">
                                            <span class="streak-value">{{ blocks.output.collectedCount || 0 }}</span>
                                            <span class="streak-label">被收藏</span>
                                        </div>
                                    </div>
                                </div>
                                <div v-else class="block-empty">暂无内容输出</div>
                            </template>
                            <div v-else class="block-empty">该板块未公开</div>
                        </div>

                        <!-- 测评成绩 -->
                        <div class="card block-card">
                            <div class="block-head">
                                <span class="block-title">测评成绩</span>
                                <span class="block-badge" v-if="isPrivate(blocks.assessment)">未公开</span>
                            </div>
                            <template v-if="!isPrivate(blocks.assessment)">
                                <div v-if="blocks.assessment && blocks.assessment.available" class="assessment-body">
                                    <div class="assessment-score">
                                        <span class="score-value">{{ blocks.assessment.score || 0 }}</span>
                                        <span class="score-unit">分</span>
                                    </div>
                                    <div class="assessment-meta">
                                        答对 {{ blocks.assessment.correctCount || 0 }}/{{ blocks.assessment.totalCount || 0 }} 题
                                        <template v-if="blocks.assessment.percentile > 0">
                                            · 超过 {{ blocks.assessment.percentile }}% 的参与者
                                        </template>
                                    </div>
                                    <div class="assessment-time" v-if="blocks.assessment.submittedTime">
                                        {{ blocks.assessment.submittedTime }}
                                    </div>
                                </div>
                                <div v-else class="block-empty">暂未测评，能力测评即将上线</div>
                            </template>
                            <div v-else class="block-empty">该板块未公开</div>
                        </div>
                    </div>

                    <!-- 隐私设置（仅本人） -->
                    <div v-if="isSelf" class="card privacy-card">
                        <div class="privacy-head">
                            <span class="block-title">公开设置</span>
                            <span class="privacy-hint">默认仅自己可见；开启公开后，持分享链接的访客可查看下方勾选的板块</span>
                        </div>
                        <div class="privacy-row master">
                            <div class="privacy-info">
                                <span class="privacy-name">公开我的能力档案</span>
                                <span class="privacy-desc">总开关：关闭时访客只能看到"未公开"占位</span>
                            </div>
                            <label class="switch">
                                <input type="checkbox" v-model="settingForm.isPublic">
                                <span class="switch-slider"></span>
                            </label>
                        </div>
                        <template v-if="settingForm.isPublic">
                            <div class="privacy-row" v-for="item in subSwitches" :key="item.key">
                                <div class="privacy-info">
                                    <span class="privacy-name">{{ item.name }}</span>
                                    <span class="privacy-desc">{{ item.desc }}</span>
                                </div>
                                <label class="switch">
                                    <input type="checkbox" v-model="settingForm[item.key]">
                                    <span class="switch-slider"></span>
                                </label>
                            </div>
                        </template>
                        <div class="privacy-actions">
                            <button class="primary-btn" :disabled="savingSetting" @click="saveSetting">
                                {{ savingSetting ? '保存中...' : '保存设置' }}
                            </button>
                            <span class="save-tip" v-if="settingSaved">已保存</span>
                        </div>
                    </div>
                </template>
            </template>
        </template>
    </div>
</template>

<script>
    import {
        getMyAbilityProfile,
        getUserAbilityProfile,
        getAbilitySetting,
        updateAbilitySetting
    } from '@/apis/coding'
    import { toast } from '@/utils/toast'

    export default {
        name: 'CodingAbility',
        data() {
            return {
                loading: false,
                loadError: '',
                needLogin: false,
                profile: null,
                savingSetting: false,
                settingSaved: false,
                defaultAvatar: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 80 80"%3E%3Crect width="80" height="80" fill="%23e5e6eb"/%3E%3Ccircle cx="40" cy="30" r="14" fill="%23c9cdd4"/%3E%3Cpath d="M12 72c4-16 16-24 28-24s24 8 28 24z" fill="%23c9cdd4"/%3E%3C/svg%3E',
                settingForm: {
                    isPublic: false,
                    publicDomain: true,
                    publicStreak: true,
                    publicOutput: true,
                    publicAssessment: true
                }
            }
        },
        computed: {
            isLoggedIn() {
                return this.$store.getters.isLoggedIn
            },
            // 公开页路由参数（/user/:id/ability）；本人页为 null
            routeUserId() {
                const id = this.$route.params && this.$route.params.id
                return id ? String(id) : null
            },
            selfUserId() {
                const info = this.$store.getters.userInfo || {}
                return info.userId ? String(info.userId) : null
            },
            // 本人视角：/coding/ability 固定本人；公开页则比对当前登录用户ID
            isSelf() {
                if (!this.routeUserId) {
                    return true
                }
                return this.isLoggedIn && this.selfUserId === this.routeUserId
            },
            blocks() {
                return (this.profile && this.profile.blocks) || {}
            },
            domainItems() {
                const block = this.blocks.domain
                return (block && block.items) || []
            },
            subSwitches() {
                return [
                    { key: 'publicDomain', name: '技术领域分布', desc: '答题沉淀的技术领域与正确情况' },
                    { key: 'publicStreak', name: '持续度', desc: '连续答题天数与活跃月份（会暴露活跃时间，请谨慎公开）' },
                    { key: 'publicOutput', name: '输出能力', desc: '已发布文章数与被收藏数' },
                    { key: 'publicAssessment', name: '测评成绩', desc: '最近一次能力测评的分数与百分位' }
                ]
            }
        },
        mounted() {
            this.loadProfile()
        },
        methods: {
            // 板块是否被主人设置为未公开（访客视角才有意义；本人视角为开关回显）
            isPrivate(block) {
                return block && block.public === false
            },
            domainRate(item) {
                const total = (item && item.total) || 0
                if (!total) {
                    return 0
                }
                return Math.round(((item.correct || 0) * 100) / total)
            },
            showLogin() {
                this.$store.dispatch('showLogin')
            },
            goDaily() {
                this.$router.push('/coding').catch(() => {})
            },
            async loadProfile() {
                this.loadError = ''
                // /coding/ability：本人视角，需登录
                if (!this.routeUserId) {
                    if (!this.isLoggedIn) {
                        this.needLogin = true
                        this.profile = null
                        return
                    }
                    this.loading = true
                    try {
                        const res = await getMyAbilityProfile()
                        if (res && res.code === 200 && res.data) {
                            this.profile = res.data
                            this.loadSetting()
                        } else {
                            this.loadError = (res && res.message) || '档案加载失败，请稍后重试'
                        }
                    } catch (e) {
                        this.loadError = '档案加载失败，请稍后重试'
                    } finally {
                        this.loading = false
                    }
                    return
                }
                // /user/:id/ability：公开视角（匿名可访问；本人登录访问时后端返回全量）
                this.loading = true
                try {
                    const res = await getUserAbilityProfile(this.routeUserId)
                    if (res && res.code === 200 && res.data) {
                        this.profile = res.data
                        if (this.isSelf) {
                            this.loadSetting()
                        }
                    } else {
                        this.loadError = (res && res.message) || '档案加载失败，请稍后重试'
                    }
                } catch (e) {
                    this.loadError = '档案加载失败，请稍后重试'
                } finally {
                    this.loading = false
                }
            },
            async loadSetting() {
                try {
                    const res = await getAbilitySetting()
                    if (res && res.code === 200 && res.data) {
                        this.settingForm = {
                            isPublic: !!res.data.isPublic,
                            publicDomain: res.data.publicDomain !== false,
                            publicStreak: res.data.publicStreak !== false,
                            publicOutput: res.data.publicOutput !== false,
                            publicAssessment: res.data.publicAssessment !== false
                        }
                    }
                } catch (e) {
                    // 开关加载失败不阻塞档案主体展示
                }
            },
            async saveSetting() {
                if (this.savingSetting) {
                    return
                }
                this.savingSetting = true
                this.settingSaved = false
                try {
                    const res = await updateAbilitySetting({
                        isPublic: this.settingForm.isPublic,
                        publicDomain: this.settingForm.publicDomain,
                        publicStreak: this.settingForm.publicStreak,
                        publicOutput: this.settingForm.publicOutput,
                        publicAssessment: this.settingForm.publicAssessment
                    })
                    if (res && res.code === 200) {
                        this.settingSaved = true
                        toast(this.settingForm.isPublic ? '已公开，分享链接可被访客查看' : '已设为私有，仅自己可见', 2)
                        // 重新拉取档案：刷新 isPublic 与各板块公开标记回显
                        this.loadProfile()
                    } else {
                        toast((res && res.message) || '保存失败，请稍后重试', 2)
                    }
                } catch (e) {
                    toast('网络异常，请稍后重试', 2)
                } finally {
                    this.savingSetting = false
                }
            },
            copyShareLink() {
                const id = this.routeUserId || this.selfUserId
                if (!id) {
                    toast('请先登录后再分享', 2)
                    return
                }
                const url = window.location.origin + '/user/' + id + '/ability'
                const fallbackCopy = () => {
                    try {
                        const input = document.createElement('input')
                        input.value = url
                        document.body.appendChild(input)
                        input.select()
                        document.execCommand('copy')
                        document.body.removeChild(input)
                        toast('分享链接已复制', 2)
                    } catch (e) {
                        toast('复制失败，请手动复制地址栏链接', 2)
                    }
                }
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(url).then(() => {
                        toast(this.profile && this.profile.isPublic ? '分享链接已复制' : '分享链接已复制（公开后访客才可见）', 2)
                    }).catch(fallbackCopy)
                } else {
                    fallbackCopy()
                }
            }
        }
    }
</script>

<style scoped>
    .ability-page {
        padding-bottom: 40PX;
        min-height: 60vh;
    }

    .ability-page * {
        box-sizing: border-box;
    }

    /* 头部 */
    .ability-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        background: linear-gradient(120deg, #1E80FF 0%, #4A9BFF 100%);
        border-radius: 10PX;
        padding: 22PX 28PX;
        color: #fff;
        margin-bottom: 16PX;
        flex-wrap: wrap;
        gap: 12PX;
    }

    .page-title {
        margin: 0 0 6PX;
        font-size: 24PX;
        font-weight: 600;
    }

    .page-sub {
        font-size: 13PX;
        opacity: 0.9;
    }

    .header-right {
        display: flex;
        align-items: center;
        gap: 14PX;
    }

    .ghost-btn {
        border: 1PX solid rgba(255, 255, 255, 0.8);
        background: transparent;
        color: #fff;
        border-radius: 16PX;
        padding: 7PX 16PX;
        font-size: 13PX;
        cursor: pointer;
    }

    .ghost-btn:hover {
        background: rgba(255, 255, 255, 0.15);
    }

    /* 卡片与状态 */
    .card {
        background: #fff;
        border-radius: 8PX;
        padding: 18PX 20PX;
    }

    .state-card {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 10PX;
        padding: 48PX 20PX;
        color: #86909c;
    }

    .state-icon {
        font-size: 34PX;
    }

    .state-title {
        font-size: 16PX;
        color: #1d2129;
        font-weight: 500;
    }

    .state-desc {
        font-size: 13PX;
        color: #86909c;
    }

    .primary-btn {
        background: #1E80FF;
        color: #fff;
        border: none;
        border-radius: 6PX;
        padding: 9PX 26PX;
        font-size: 14PX;
        cursor: pointer;
    }

    .primary-btn:disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }

    /* 身份行 */
    .identity-card {
        display: flex;
        align-items: center;
        gap: 16PX;
        margin-bottom: 16PX;
    }

    .identity-avatar {
        width: 64PX;
        height: 64PX;
        border-radius: 50%;
        object-fit: cover;
        flex-shrink: 0;
    }

    .identity-name {
        font-size: 18PX;
        font-weight: 600;
        color: #1d2129;
        margin-bottom: 6PX;
    }

    .identity-sub {
        font-size: 13PX;
        color: #86909c;
    }

    /* 板块网格 */
    .blocks-grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 16PX;
    }

    .block-card {
        display: flex;
        flex-direction: column;
        min-height: 180PX;
    }

    .block-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 14PX;
    }

    .block-title {
        font-size: 15PX;
        font-weight: 600;
        color: #1d2129;
    }

    .block-badge {
        font-size: 12PX;
        color: #86909c;
        border: 1PX solid #e5e6eb;
        border-radius: 10PX;
        padding: 1PX 8PX;
    }

    .block-empty {
        flex: 1;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 13PX;
        color: #c9cdd4;
        text-align: center;
        padding: 20PX 0;
    }

    /* 领域分布 */
    .domain-list {
        display: flex;
        flex-direction: column;
        gap: 12PX;
    }

    .domain-item {
        display: flex;
        align-items: center;
        gap: 10PX;
    }

    .domain-name {
        width: 90PX;
        font-size: 13PX;
        color: #4e5969;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        flex-shrink: 0;
    }

    .domain-bar {
        flex: 1;
        height: 8PX;
        background: #f2f3f5;
        border-radius: 4PX;
        overflow: hidden;
    }

    .domain-bar-inner {
        height: 100%;
        background: linear-gradient(90deg, #1E80FF, #4A9BFF);
        border-radius: 4PX;
        transition: width 0.3s ease;
    }

    .domain-num {
        width: 52PX;
        text-align: right;
        font-size: 12PX;
        color: #86909c;
        flex-shrink: 0;
    }

    /* 持续度 / 输出能力 */
    .streak-body {
        flex: 1;
        display: flex;
        flex-direction: column;
        justify-content: center;
        gap: 12PX;
    }

    .streak-nums {
        display: flex;
        gap: 40PX;
    }

    .streak-cell {
        display: flex;
        flex-direction: column;
        gap: 6PX;
    }

    .streak-value {
        font-size: 30PX;
        font-weight: 600;
        color: #1E80FF;
        line-height: 1;
    }

    .streak-label {
        font-size: 12PX;
        color: #86909c;
    }

    .streak-dates {
        font-size: 12PX;
        color: #86909c;
    }

    /* 测评成绩 */
    .assessment-body {
        flex: 1;
        display: flex;
        flex-direction: column;
        justify-content: center;
        gap: 8PX;
    }

    .assessment-score {
        display: flex;
        align-items: baseline;
        gap: 4PX;
    }

    .score-value {
        font-size: 34PX;
        font-weight: 600;
        color: #1E80FF;
        line-height: 1;
    }

    .score-unit {
        font-size: 13PX;
        color: #86909c;
    }

    .assessment-meta {
        font-size: 13PX;
        color: #4e5969;
    }

    .assessment-time {
        font-size: 12PX;
        color: #c9cdd4;
    }

    /* 隐私设置 */
    .privacy-card {
        margin-top: 16PX;
    }

    .privacy-head {
        display: flex;
        align-items: baseline;
        gap: 10PX;
        margin-bottom: 6PX;
        flex-wrap: wrap;
    }

    .privacy-hint {
        font-size: 12PX;
        color: #86909c;
    }

    .privacy-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 16PX;
        padding: 14PX 0;
        border-bottom: 1PX solid #f2f3f5;
    }

    .privacy-row.master {
        border-bottom: 1PX solid #f2f3f5;
    }

    .privacy-info {
        display: flex;
        flex-direction: column;
        gap: 4PX;
    }

    .privacy-name {
        font-size: 14PX;
        color: #1d2129;
    }

    .privacy-desc {
        font-size: 12PX;
        color: #86909c;
    }

    /* 开关 */
    .switch {
        position: relative;
        display: inline-block;
        width: 42PX;
        height: 24PX;
        flex-shrink: 0;
    }

    .switch input {
        opacity: 0;
        width: 0;
        height: 0;
    }

    .switch-slider {
        position: absolute;
        inset: 0;
        background: #c9cdd4;
        border-radius: 12PX;
        transition: background 0.2s ease;
        cursor: pointer;
    }

    .switch-slider::before {
        content: '';
        position: absolute;
        width: 20PX;
        height: 20PX;
        left: 2PX;
        top: 2PX;
        background: #fff;
        border-radius: 50%;
        transition: transform 0.2s ease;
    }

    .switch input:checked + .switch-slider {
        background: #1E80FF;
    }

    .switch input:checked + .switch-slider::before {
        transform: translateX(18PX);
    }

    .privacy-actions {
        display: flex;
        align-items: center;
        gap: 12PX;
        padding-top: 16PX;
    }

    .save-tip {
        font-size: 13PX;
        color: #00b42a;
    }

    /* 窄屏：板块改为单列 */
    @media (max-width: 768PX) {
        .blocks-grid {
            grid-template-columns: 1fr;
        }
    }
</style>