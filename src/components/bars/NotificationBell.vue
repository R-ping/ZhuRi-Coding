<template>
    <div class="notification-bell" @mouseenter="onMouseEnter" @mouseleave="onMouseLeave">
        <span class="bell-icon" @click.stop="$emit('go-to-notification', hasUnreadType)">&#xf0f3;</span>
        <span v-if="unreadTotal > 0" class="unread-badge">{{ unreadTotal > 99 ? '99+' : unreadTotal }}</span>
        <div class="dropdown-wrapper" v-if="showDropdown" @mouseenter="onMouseEnter" @mouseleave="onMouseLeave">
            <div class="notification-dropdown">
                <div class="dropdown-item" @click.stop="$emit('go-to-notification', 'comment')">
                    <span>评论</span>
                    <span v-if="unreadCounts.comment > 0" class="item-unread">{{ unreadCounts.comment > 99 ? '99+' : unreadCounts.comment }}</span>
                </div>
                <div class="dropdown-item" @click.stop="$emit('go-to-notification', 'like')">
                    <span>赞和收藏</span>
                    <span v-if="unreadCounts.digg > 0" class="item-unread">{{ unreadCounts.digg > 99 ? '99+' : unreadCounts.digg }}</span>
                </div>
                <div class="dropdown-item" @click.stop="$emit('go-to-notification', 'follow')">
                    <span>新增粉丝</span>
                    <span v-if="unreadCounts.follow > 0" class="item-unread">{{ unreadCounts.follow > 99 ? '99+' : unreadCounts.follow }}</span>
                </div>
                <div class="dropdown-item" @click.stop="$emit('go-to-notification', 'message')">
                    <span>私信</span>
                </div>
                <div class="dropdown-item" @click.stop="$emit('go-to-notification', 'system')">
                    <span>系统通知</span>
                    <span v-if="unreadCounts.system > 0" class="item-unread">{{ unreadCounts.system > 99 ? '99+' : unreadCounts.system }}</span>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
export default {
    name: 'NotificationBell',
    props: {
        unreadTotal: {
            type: Number,
            default: 0
        },
        unreadCounts: {
            type: Object,
            default: () => ({ comment: 0, digg: 0, follow: 0, system: 0 })
        }
    },
    computed: {
        hasUnreadType() {
            if (this.unreadCounts.system > 0) return 'system';
            if (this.unreadCounts.comment > 0) return 'comment';
            if (this.unreadCounts.digg > 0) return 'like';
            if (this.unreadCounts.follow > 0) return 'follow';
            return 'comment';
        }
    },
    data() {
        return {
            showDropdown: false,
            hideTimer: null
        }
    },
    methods: {
        onMouseEnter() {
            if (this.hideTimer) {
                clearTimeout(this.hideTimer)
                this.hideTimer = null
            }
            this.showDropdown = true
        },
        onMouseLeave() {
            var self = this
            this.hideTimer = setTimeout(function() {
                self.showDropdown = false
                self.hideTimer = null
            }, 200)
        }
    },
    beforeDestroy() {
        if (this.hideTimer) {
            clearTimeout(this.hideTimer)
            this.hideTimer = null
        }
    }
}
</script>

<style lang="less" scoped>
.notification-bell {
    position: relative;
    width: 36px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    cursor: pointer;
    transition: background-color 0.2s;
    flex-shrink: 0;
}
.notification-bell:hover {
    background-color: #f4f5f5;
}
.bell-icon {
    font-family: fontawesome;
    font-size: 20px;
    color: #515767;
}
.unread-badge {
    position: absolute;
    top: 0;
    right: 0;
    min-width: 16px;
    height: 16px;
    line-height: 16px;
    text-align: center;
    background: #ff4d4f;
    color: #fff;
    font-size: 10px;
    border-radius: 8px;
    padding: 0 4px;
    transform: translate(30%, -30%);
}
.dropdown-wrapper {
    position: absolute;
    top: 100%;
    right: 0;
    padding-top: 8px;
    min-width: 160px;
    z-index: 300;
}
.notification-dropdown {
    background-color: #ffffff;
    border-radius: 8px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.12);
    overflow: hidden;
}
.dropdown-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 16px;
    font-size: 14px;
    color: #333;
    cursor: pointer;
    white-space: nowrap;
    transition: background-color 0.2s;
}
.dropdown-item:hover {
    background-color: #f5f7fa;
}
.item-unread {
    display: inline-block;
    min-width: 16px;
    height: 16px;
    line-height: 16px;
    text-align: center;
    background: #ff4d4f;
    color: #fff;
    font-size: 10px;
    border-radius: 8px;
    padding: 0 4px;
    margin-left: 8px;
}
</style>
