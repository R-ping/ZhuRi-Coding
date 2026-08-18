<template>
    <div
        class="user-dropdown"
        :style="dropdownStyle"
        @click.stop
    >
        <!-- 用户信息区 -->
        <div class="dropdown-user-section" @click="$emit('go-profile')">
            <img v-if="userAvatar" class="dropdown-avatar" :src="userAvatar" alt="头像" />
            <span v-else class="dropdown-avatar-icon">&#xf007;</span>
            <div class="dropdown-user-info">
                <div class="dropdown-username">{{ userName }}</div>
                <div class="dropdown-diamond-row" @click.stop>
                    <span class="diamond-icon">&#xf219;</span>
                    <span class="diamond-text">矿石: {{ formattedDiamond }}</span>
                </div>
            </div>
        </div>
        <!-- 等级进度条 -->
        <div class="dropdown-level-bar" @click="$emit('go-growth')">
            <div class="level-label">逐日等级 {{ levelBadge }}</div>
            <div class="level-progress-wrap">
                <div class="level-progress-bar">
                    <div class="level-progress-fill" :style="{ width: levelPercent + '%' }"></div>
                    <span class="level-progress-pointer" :style="{ left: levelPercent + '%' }"></span>
                </div>
                <span class="level-text">{{ formattedLevelText }}</span>
            </div>
            <span class="level-arrow">&#xf105;</span>
        </div>
        <!-- 统计数据 -->
        <div class="dropdown-stats">
            <div class="stat-item" @click="$emit('go-follow')">
                <div class="stat-value">{{ stats.followCount }}</div>
                <div class="stat-label">关注</div>
            </div>
            <div class="stat-item" @click="$emit('go-likes')">
                <div class="stat-value">{{ stats.likeCount }}</div>
                <div class="stat-label">赞过</div>
            </div>
            <div class="stat-item" @click="$emit('go-collects')">
                <div class="stat-value">{{ stats.collectCount }}</div>
                <div class="stat-label">收藏</div>
            </div>
        </div>
        <div class="dropdown-divider"></div>
        <!-- 菜单项 -->
        <div class="dropdown-menu-section">
            <div class="dropdown-item" @click="$emit('go-profile')">
                <span class="dropdown-icon">&#xf007;</span>
                <span class="dropdown-label">我的主页</span>
            </div>
            <div class="dropdown-item" @click="$emit('go-checkin')">
                <span class="dropdown-icon">&#xf091;</span>
                <span class="dropdown-label">成长福利</span>
            </div>
            <div class="dropdown-item" @click="$emit('go-courses')">
                <span class="dropdown-icon">&#xf19c;</span>
                <span class="dropdown-label">课程中心</span>
            </div>
            <div class="dropdown-item" @click="$emit('go-settings')">
                <span class="dropdown-icon">&#xf013;</span>
                <span class="dropdown-label">我的设置</span>
            </div>
            <div class="dropdown-item logout-item" @click="$emit('logout')">
                <span class="dropdown-icon">&#xf08b;</span>
                <span class="dropdown-label">退出登录</span>
            </div>
        </div>
    </div>
</template>

<script>
export default {
    name: 'UserDropdown',
    props: {
        userAvatar: { type: String, default: '' },
        userName: { type: String, default: '用户' },
        levelBadge: { type: String, default: 'ZR.1' },
        formattedDiamond: { type: String, default: '0' },
        levelPercent: { type: Number, default: 0 },
        formattedLevelText: { type: String, default: '0 / 150' },
        stats: {
            type: Object,
            default: () => ({ followCount: 0, likeCount: 0, collectCount: 0 })
        }
    },
    computed: {
        dropdownStyle() {
            // 使用固定定位时，位置由父组件计算后传入
            // 这里返回空对象，实际定位由父组件通过绝对定位或 fixed 控制
            return {};
        }
    }
}
</script>

<style lang="less" scoped>
.user-dropdown {
    position: absolute;
    top: 100%;
    right: 0;
    margin-top: 8px;
    background-color: #ffffff;
    border-radius: 16px;
    box-shadow: 0 8px 32px rgba(0,0,0,0.15);
    width: 280px;
    z-index: 999;
    overflow: hidden;
    border: 1px solid rgba(0,0,0,0.06);
}

.dropdown-user-section {
    display: flex;
    align-items: center;
    padding: 12px 16px 8px;
    cursor: pointer;
    gap: 12px;
}
.dropdown-user-section:hover {
    background-color: #f7f8fa;
}
.dropdown-avatar {
    width: 56px;
    height: 56px;
    border-radius: 50%;
    object-fit: cover;
    border: 2px solid #1e80ff;
}
.dropdown-avatar-icon {
    width: 56px;
    height: 56px;
    border-radius: 50%;
    background: #e4e6eb;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: fontawesome;
    font-size: 24px;
    color: #8a919f;
}
.dropdown-user-info {
    flex: 1;
}
.dropdown-username {
    font-size: 16px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 4px;
}
.dropdown-diamond-row {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 2px 8px;
    background: #fff7e6;
    border-radius: 10px;
    width: fit-content;
    cursor: pointer;
    .diamond-icon {
        font-family: fontawesome;
        font-size: 12px;
        color: #fa8c16;
    }
    .diamond-text {
        font-size: 12px;
        color: #fa8c16;
    }
}

.dropdown-level-bar {
    display: flex;
    align-items: center;
    padding: 4px 16px;
    cursor: pointer;
    gap: 8px;
    &:hover { background-color: #f7f8fa; }
    .level-label {
        font-size: 12px;
        color: #1e80ff;
        white-space: nowrap;
    }
    .level-progress-wrap {
        flex: 1;
        display: flex;
        align-items: center;
        gap: 6px;
        position: relative;
    }
    .level-progress-bar {
        flex: 1;
        height: 6px;
        background: #e4e6eb;
        border-radius: 3px;
        /* 允许 pointer 溢出显示 */
        overflow: visible;
        position: relative;
    }
    .level-progress-fill {
        height: 100%;
        background: linear-gradient(90deg, #1e80ff, #4096ff);
        border-radius: 3px;
        transition: width 0.3s;
        /* 裁切 fill 本身，防止超出圆角 */
        overflow: hidden;
        border-radius: 3px;
    }
    /* 进度指针：蓝色竖线 + 底部三角，精确指向当前经验所在位置 */
    .level-progress-pointer {
        position: absolute;
        top: 50%;
        left: 0;
        transform: translate(-50%, -50%);
        width: 2px;
        height: 16px;
        background-color: #1e80ff;
        border-radius: 1px;
        pointer-events: none;
        transition: left 0.3s;
        /* 提高层级 */
        z-index: 10;
    }
    .level-progress-pointer::after {
        content: '';
        position: absolute;
        left: 50%;
        bottom: -6px;
        transform: translateX(-50%);
        border-left: 4px solid transparent;
        border-right: 4px solid transparent;
        border-top: 5px solid #1e80ff;
    }
    .level-text {
        font-size: 11px;
        color: #8a919f;
        white-space: nowrap;
    }
    .level-arrow {
        font-family: fontawesome;
        font-size: 14px;
        color: #8a919f;
    }
}

.dropdown-stats {
    display: flex;
    justify-content: space-around;
    padding: 4px 16px 0;
}
.dropdown-stats .stat-item {
    text-align: center;
    cursor: pointer;
    padding: 4px 12px;
    border-radius: 8px;
    &:hover { background-color: #f7f8fa; }
}
.dropdown-stats .stat-value {
    font-size: 18px;
    font-weight: 600;
    color: #252933;
}
.dropdown-stats .stat-label {
    font-size: 12px;
    color: #8a919f;
    margin-top: 2px;
}

.dropdown-divider {
    height: 1px;
    background: #f2f3f5;
    margin: 2px 0;
}

.dropdown-menu-section {
    padding: 2px 0;
}

.dropdown-bottom-section {
    padding: 2px 0;
}

.dropdown-item {
    display: flex;
    align-items: center;
    padding: 8px 16px;
    cursor: pointer;
    transition: background-color 0.2s;
    gap: 8px;
}
.dropdown-item:hover {
    background-color: #f5f5f5;
}
.logout-item {
    color: #ff4d4f;
}
.logout-item:hover {
    background-color: #fff2f0;
}
.dropdown-icon {
    font-family: fontawesome;
    font-size: 14px;
    width: 16px;
    text-align: center;
}
.dropdown-label {
    font-size: 14px;
    color: #333;
    flex: 1;
}
.logout-item .dropdown-label {
    color: #ff4d4f;
}
</style>