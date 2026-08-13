<template>
    <div class="notification-page">
        <div class="notification-content">
            <div class="tabs-bar">
                <div
                    class="tab-item"
                    :class="{ 'active': activeTab === 'comment' }"
                    @click="activeTab = 'comment'"
                >
                    评论
                </div>
                <div
                    class="tab-item"
                    :class="{ 'active': activeTab === 'like' }"
                    @click="activeTab = 'like'"
                >
                    赞和收藏
                </div>
                <div
                    class="tab-item"
                    :class="{ 'active': activeTab === 'follow' }"
                    @click="activeTab = 'follow'"
                >
                    新增粉丝
                </div>
                <div
                    class="tab-item"
                    :class="{ 'active': activeTab === 'message' }"
                    @click="activeTab = 'message'"
                >
                    私信
                </div>
                <div
                    class="tab-item"
                    :class="{ 'active': activeTab === 'system' }"
                    @click="activeTab = 'system'"
                >
                    系统通知
                </div>
            </div>

            <div class="content-area">
                <div v-if="activeTab === 'comment'" class="tab-content" @scroll="onScroll($event, 'comment')">
                    <div class="empty-state" v-if="loading['comment'] === false && commentList.length === 0">
                        <img src="https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=empty%20notification%20illustration%20cute%20blue%20fish%20with%20speech%20bubble%20minimalist%20flat%20design%20for%20website&image_size=square" class="empty-img" alt="暂无消息">
                        <span class="empty-text">暂无消息</span>
                    </div>
                    <div class="notification-list" v-else>
                        <div class="notification-item" v-for="item in commentList" :key="item.id">
                            <img :src="item.userAvatar || defaultAvatar" class="notify-avatar" alt="avatar">
                            <div class="notify-content">
                                <div class="notify-header">
                                    <span class="notify-user">{{ item.userName }}</span>
                                    <span class="notify-action">{{ item.action }}</span>
                                    <span class="notify-time">{{ formatTime(item.time) }}</span>
                                </div>
                                <div class="notify-text">{{ item.content }}</div>
                                <div class="notify-article">{{ item.articleTitle }}</div>
                                <div class="notify-actions">
                                    <button class="action-btn" :class="{ 'active': item.isLiked }" @click="toggleLike(item)">
                                        <span class="action-icon">&#xf087;</span>
                                        <span class="action-text">{{ item.likeCount }}</span>
                                    </button>
                                    <button class="action-btn" @click="handleReply(item)">
                                        <span class="action-icon">&#xf112;</span>
                                        <span class="action-text">回复</span>
                                    </button>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'like'" class="tab-content" @scroll="onScroll($event, 'like')">
                    <div class="empty-state" v-if="loading['like'] === false && likeList.length === 0">
                        <img src="https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=empty%20notification%20illustration%20cute%20blue%20fish%20with%20speech%20bubble%20minimalist%20flat%20design%20for%20website&image_size=square" class="empty-img" alt="暂无消息">
                        <span class="empty-text">暂无消息</span>
                    </div>
                    <div class="notification-list" v-else>
                        <div class="notification-item" v-for="item in likeList" :key="item.id">
                            <img :src="item.userAvatar || defaultAvatar" class="notify-avatar" alt="avatar">
                            <div class="notify-content">
                                <div class="notify-header">
                                    <span class="notify-user">{{ item.userName }}</span>
                                    <span class="notify-action">{{ item.action }}</span>
                                    <span class="notify-time">{{ formatTime(item.time) }}</span>
                                </div>
                                <div class="notify-article">{{ item.articleTitle }}</div>
                            </div>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'follow'" class="tab-content" @scroll="onScroll($event, 'follow')">
                    <div class="empty-state" v-if="loading['follow'] === false && followList.length === 0">
                        <img src="https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=empty%20notification%20illustration%20cute%20blue%20fish%20with%20speech%20bubble%20minimalist%20flat%20design%20for%20website&image_size=square" class="empty-img" alt="暂无消息">
                        <span class="empty-text">暂无消息</span>
                    </div>
                    <div class="notification-list" v-else>
                        <div class="notification-item" v-for="item in followList" :key="item.id">
                            <img :src="item.userAvatar || defaultAvatar" class="notify-avatar" alt="avatar">
                            <div class="notify-content">
                                <div class="notify-header">
                                    <span class="notify-user">{{ item.userName }}</span>
                                    <span class="notify-action">{{ item.action }}</span>
                                    <span class="notify-time">{{ formatTime(item.time) }}</span>
                                </div>
                            </div>
                            <button
                                class="follow-btn"
                                :class="{ 'active': item.isFollowed }"
                                @click="toggleFollow(item)"
                            >
                                {{ item.isFollowed ? '已关注' : '关注' }}
                            </button>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'message'" class="tab-content">
                    <div class="message-layout">
                        <div class="message-sidebar">
                            <div class="search-box">
                                <input type="text" class="search-input" placeholder="搜索联系人" v-model="searchKeyword">
                            </div>
                            <div class="contact-list">
                                <div
                                    class="contact-item"
                                    :class="{ 'active': selectedContact && selectedContact.id === contact.id }"
                                    v-for="contact in filteredContacts"
                                    :key="contact.id"
                                    @click="selectContact(contact)"
                                >
                                    <img :src="contact.avatar || defaultAvatar" class="contact-avatar" alt="avatar">
                                    <div class="contact-info">
                                        <div class="contact-name">{{ contact.name }}</div>
                                        <div class="contact-last-message">{{ contact.lastMessage }}</div>
                                    </div>
                                    <div class="contact-time">{{ formatTime(contact.lastTime) }}</div>
                                </div>
                            </div>
                        </div>
                        <div class="message-chat">
                            <div v-if="!selectedContact" class="empty-chat">
                                <span class="empty-icon">💬</span>
                                <span class="empty-text">选择一个联系人开始聊天</span>
                            </div>
                            <div v-else class="chat-content">
                                <div class="chat-header">
                                    <span class="chat-title">{{ selectedContact.name }}</span>
                                </div>
                                <div class="chat-messages" ref="chatMessages">
                                    <div
                                        class="message-item"
                                        :class="{ 'is-self': msg.isSelf }"
                                        v-for="msg in selectedContact.messages"
                                        :key="msg.id"
                                    >
                                        <img :src="msg.isSelf ? (userAvatar || defaultAvatar) : (selectedContact.avatar || defaultAvatar)" class="msg-avatar" alt="avatar">
                                        <div class="msg-content">
                                            <div class="msg-text">{{ msg.content }}</div>
                                            <div class="msg-time">{{ formatTime(msg.time) }}</div>
                                        </div>
                                    </div>
                                </div>
                                <div class="chat-warning" v-if="!selectedContact.canSendUnlimited">
                                    由于对方并未关注你，在收到对方回复之前，你最多只能发送1条文字消息
                                </div>
                                <div class="chat-input-area">
                                    <button class="input-icon-btn">😊</button>
                                    <button class="input-icon-btn">📷</button>
                                    <input
                                        type="text"
                                        class="chat-input"
                                        placeholder="输入消息..."
                                        v-model="messageInput"
                                        @keyup.enter="sendMessage"
                                    >
                                    <button class="send-btn" @click="sendMessage">发送</button>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'system'" class="tab-content" @scroll="onScroll($event, 'system')">
                    <div class="empty-state" v-if="loading['system'] === false && systemList.length === 0">
                        <img src="https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=empty%20notification%20illustration%20cute%20blue%20fish%20with%20speech%20bubble%20minimalist%20flat%20design%20for%20website&image_size=square" class="empty-img" alt="暂无消息">
                        <span class="empty-text">暂无消息</span>
                    </div>
                    <div class="notification-list" v-else>
                        <div class="notification-item" v-for="item in systemList" :key="item.id">
                            <div class="notify-content system-content">
                                <!-- 左侧产品/系统图标 -->
                                <div class="system-icon-wrap">
                                    <span class="system-icon">{{ getSystemIcon(item.notificationType) }}</span>
                                </div>
                                <!-- 中间主体内容 -->
                                <div class="system-main">
                                    <div class="notify-header">
                                        <span class="system-type-tag" :class="'tag-' + (item.notificationType || 'system')">{{ getSystemTypeLabel(item.notificationType) }}</span>
                                    </div>
                                    <div class="notify-text">{{ item.content }}</div>
                                    <div class="notify-time">{{ formatTime(item.time) }}</div>
                                </div>
                                <!-- 右侧沸点概览 -->
                                <div class="system-preview" v-if="item.preview">
                                    <span class="preview-text">{{ item.preview.substring(0, 12) }}{{ item.preview.length > 12 ? '...' : '' }}</span>
                                    <span class="preview-tag">沸点</span>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
const defaultAvatar = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'
import request from '@/common/request'
import conf from '@/common/conf'
import { toast } from '@/utils/toast'

export default {
    name: 'Notification',
    data() {
        return {
            _initialized: false,
            activeTab: 'comment',
            searchKeyword: '',
            selectedContact: null,
            messageInput: '',
            userAvatar: '',
            commentList: [],
            likeList: [],
            followList: [],
            systemList: [],
            contacts: [],
            cursors: { comment: null, like: null, follow: null, system: null },
            hasMore: { comment: true, like: true, follow: true, system: true },
            loading: { comment: false, like: false, follow: false, system: false },
            loadingMore: { comment: false, like: false, follow: false, system: false },
            sessionsLoading: false,
            messagesLoading: false,
            messagesCursor: null,
            messagesHasMore: true
        }
    },
    computed: {
        defaultAvatar() {
            return defaultAvatar
        },
        filteredContacts() {
            if (!this.searchKeyword) return this.contacts
            return this.contacts.filter(c => (c.name || '').includes(this.searchKeyword))
        }
    },
    watch: {
        activeTab: function(newTab) {
            if (!this._initialized) return
            this.switchTab(newTab)
        },
        '$route.query.tab': function(newTab) {
            if (!this._initialized) return
            if (newTab && ['comment', 'like', 'follow', 'message', 'system'].includes(newTab) && newTab !== this.activeTab) {
                this.activeTab = newTab
            }
        },
        '$route.query.peer_id': function(newPeerId) {
            if (!this._initialized) return
            if (newPeerId) {
                if (this.activeTab !== 'message') {
                    this.activeTab = 'message'
                    this.loadSessions()
                }
                this.openPeerConversation(newPeerId)
            }
        }
    },
    mounted() {
        const tab = this.$route.query.tab
        if (tab && ['comment', 'like', 'follow', 'message', 'system'].includes(tab)) {
            this.activeTab = tab
        } else {
            this.activeTab = 'comment'
        }
        this._initialized = true
        this.switchTab(this.activeTab)
        // 从作者卡片「私信」按钮跳转而来：打开私信分栏并选中该用户
        const peerId = this.$route.query.peer_id
        if (this.activeTab === 'message' && peerId) {
            this.openPeerConversation(peerId)
        }
    },
    methods: {
        formatTime(timestamp) {
            if (!timestamp) return ''
            var now = Date.now()
            var t = typeof timestamp === 'string' ? new Date(timestamp).getTime() : timestamp
            if (isNaN(t)) return ''
            var diff = now - t
            var hours = Math.floor(diff / 3600000)
            var days = Math.floor(diff / 86400000)
            var months = Math.floor(diff / 2592000000)
            if (hours < 1) return '刚刚'
            if (hours < 24) return hours + '小时前'
            if (days < 30) return days + '天前'
            if (months < 12) return months + '个月前'
            return Math.floor(months / 12) + '年前'
        },

        getNotificationUrl(name) {
            return conf.urls.get(name)
        },

        loadNotifications(type) {
            var self = this
            var stateKey = type
            if (self.loading[stateKey]) return
            self.$set(self.loading, stateKey, true)
            self.$set(self.cursors, stateKey, null)
            self.$set(self.hasMore, stateKey, true)

            var url = self.getNotificationUrl('notifications_list')
            request.get(url, { type: type, size: 20 }).then(function(d) {
                self.$set(self.loading, stateKey, false)
                if (d && d.code === 200 && d.data) {
                    var data = d.data
                    self.$set(self.cursors, stateKey, data.next_cursor)
                    self.$set(self.hasMore, stateKey, data.has_more)
                    var list = (data.list || []).map(function(item) {
                        return self.mapNotificationItem(item)
                    })
                    self.updateList(type, list, false)
                }
            }).catch(function() {
                self.$set(self.loading, stateKey, false)
            })
        },

        loadMoreNotifications(type) {
            var self = this
            var stateKey = type
            if (self.loadingMore[stateKey] || !self.hasMore[stateKey]) return
            self.$set(self.loadingMore, stateKey, true)

            var url = self.getNotificationUrl('notifications_list')
            var cursor = self.cursors[stateKey]
            request.get(url, { type: type, size: 20, cursor: cursor }).then(function(d) {
                self.$set(self.loadingMore, stateKey, false)
                if (d && d.code === 200 && d.data) {
                    var data = d.data
                    self.$set(self.cursors, stateKey, data.next_cursor)
                    self.$set(self.hasMore, stateKey, data.has_more)
                    var list = (data.list || []).map(function(item) {
                        return self.mapNotificationItem(item)
                    })
                    self.updateList(type, list, true)
                }
            }).catch(function() {
                self.$set(self.loadingMore, stateKey, false)
            })
        },

        mapNotificationItem(item) {
            return {
                id: item.notification_id,
                userAvatar: (item.trigger_user && item.trigger_user.avatar) || '',
                userName: (item.trigger_user && item.trigger_user.name) || '用户',
                action: item.action_type || '',
                content: item.message || item.content_preview || '',
                preview: item.preview || '',
                articleTitle: item.target_title || item.title || '',
                time: item.created_at ? new Date(item.created_at).getTime() : Date.now(),
                isLiked: item.is_liked_by_me || false,
                likeCount: item.interaction_stats ? (item.interaction_stats.likes || 0) : 0,
                isFollowed: item.is_followed_back || false,
                commentId: item.comment_id,
                targetType: item.target_type,
                targetId: item.target_id,
                isRead: item.is_read,
                notificationType: item.notification_type || item.type || '',
                link: item.link || ''
            }
        },

        updateList(type, list, append) {
            var key = type + 'List'
            if (append) {
                var current = this[key] || []
                this.$set(this, key, current.concat(list))
            } else {
                this.$set(this, key, list)
            }
        },

        switchTab(tab) {
            if (tab === 'message') {
                this.loadSessions()
            } else {
                this.clearTypeUnread(tab)
                this.loadNotifications(tab)
            }
        },

        // Tab值 -> 后端通知type（comment/digg/follow/system），私信(message)走IM不在此列
        mapTabToType(tab) {
            var map = {
                comment: 'comment',
                like: 'digg',
                follow: 'follow',
                system: 'system'
            }
            return map[tab] || null
        },

        // 按类型标记已读并清除该类型未读计数，不影响其他类型
        clearTypeUnread(tab) {
            var type = this.mapTabToType(tab)
            if (!type) return
            var self = this
            var url = self.getNotificationUrl('notifications_mark_type_read')
            request.post(url, {}, { type: type }).then(function(d) {
                if (d && d.code === 200) {
                    // 通知头部/铃铛刷新未读总数与分类型计数
                    window.dispatchEvent(new CustomEvent('notification-unread-cleared'))
                }
            }).catch(function() {})
        },

        toggleLike(item) {
            var self = this
            var url = self.getNotificationUrl('notifications_like')
            request.post(url, { comment_id: String(item.commentId) }, {}).then(function(d) {
                if (d && d.code === 200) {
                    item.isLiked = !item.isLiked
                    item.likeCount += item.isLiked ? 1 : -1
                }
            }).catch(function() {
                toast('操作失败', 2)
            })
        },

        handleReply(item) {
            var content = prompt('请输入回复内容:')
            if (!content) return
            var self = this
            var url = self.getNotificationUrl('notifications_reply')
            request.post(url, { comment_id: String(item.commentId), content: content }, {}).then(function(d) {
                if (d && d.code === 200) {
                    toast('回复成功', 2)
                }
            }).catch(function() {
                toast('回复失败', 2)
            })
        },

        toggleFollow(item) {
            var self = this
            var url = self.getNotificationUrl('notifications_follow_back')
            request.post(url, { follower_id: String(item.id) }, {}).then(function(d) {
                if (d && d.code === 200) {
                    item.isFollowed = !item.isFollowed
                    toast(item.isFollowed ? '已关注' : '已取消关注', 2)
                }
            }).catch(function() {
                toast('操作失败', 2)
            })
        },

        loadSessions() {
            var self = this
            if (self.sessionsLoading) return
            self.sessionsLoading = true
            var url = self.getNotificationUrl('im_sessions')
            request.get(url, {}).then(function(d) {
                self.sessionsLoading = false
                if (d && d.code === 200 && d.data) {
                    var list = (d.data.list || []).map(function(s) {
                        return {
                            id: s.session_id,
                            name: s.peer_name || ('用户' + s.peer_id),
                            peerId: s.peer_id,
                            avatar: s.peer_avatar || '',
                            lastMessage: s.last_message || '',
                            lastTime: s.last_message_at ? new Date(s.last_message_at).getTime() : Date.now(),
                            isMutualFollow: !!(s.can_send_unlimited),
                            isActive: s.is_active || false,
                            canSendUnlimited: !!(s.can_send_unlimited),
                            unreadCount: s.unread_count || 0,
                            sentCount: 0,
                            messages: []
                        }
                    })
                    self.contacts = list
                    if (self.selectedContact) {
                        var found = list.find(function(c) { return c.id === self.selectedContact.id })
                        if (found) {
                            self.selectedContact = found
                            self.loadMessages(self.selectedContact.id)
                        }
                    }
                }
            }).catch(function() {
                self.sessionsLoading = false
            })
        },

        loadMessages(sessionId) {
            var self = this
            if (self.messagesLoading) return
            self.messagesLoading = true
            self.messagesCursor = null
            self.messagesHasMore = true
            var url = self.getNotificationUrl('im_messages')
            request.get(url, { session_id: sessionId, size: 20 }).then(function(d) {
                self.messagesLoading = false
                if (d && d.code === 200 && d.data) {
                    var data = d.data
                    self.messagesCursor = data.next_cursor
                    self.messagesHasMore = data.has_more
                    var list = (data.list || []).map(function(m) {
                        return {
                            id: m.id,
                            content: m.content,
                            time: m.created_at ? new Date(m.created_at).getTime() : Date.now(),
                            isSelf: m.is_self,
                            status: m.status
                        }
                    })
                    if (self.selectedContact) {
                        self.$set(self.selectedContact, 'messages', list)
                    }
                    if (list.length > 0) {
                        self.markMessagesRead(sessionId, list[list.length - 1].id)
                    }
                }
            }).catch(function() {
                self.messagesLoading = false
            })
        },

        markMessagesRead(sessionId, lastReadId) {
            var url = this.getNotificationUrl('im_read')
            request.post(url, { session_id: sessionId, last_read_id: lastReadId }, {}).catch(function() {})
        },

        selectContact(contact) {
            this.selectedContact = contact
            this.loadMessages(contact.id)
        },

        // 从作者卡片跳转：对指定用户打开（不存在则新建）会话并选中聊天区
        openPeerConversation(peerId) {
            var self = this
            peerId = Number(peerId)
            if (!peerId) return
            // 若会话列表已存在该用户，直接选中
            var exist = this.contacts.find(function(c) { return c.peerId === peerId })
            if (exist) {
                this.selectedContact = exist
                this.loadMessages(exist.id)
                return
            }
            var url = this.getNotificationUrl('im_session')
            request.get(url, { peer_id: peerId }).then(function(d) {
                if (d && d.code === 200 && d.data) {
                    var s = d.data
                    var contact = {
                        id: s.session_id,
                        name: s.peer_name || ('用户' + s.peer_id),
                        peerId: s.peer_id,
                        avatar: s.peer_avatar || '',
                        lastMessage: s.last_message || '',
                        lastTime: s.last_message_at ? new Date(s.last_message_at).getTime() : Date.now(),
                        isMutualFollow: !!(s.can_send_unlimited),
                        isActive: s.is_active || false,
                        canSendUnlimited: !!(s.can_send_unlimited),
                        unreadCount: s.unread_count || 0,
                        sentCount: 0,
                        messages: []
                    }
                    // 追加到联系人列表并选中
                    var exists = self.contacts.some(function(c) { return c.peerId === contact.peerId })
                    if (!exists) {
                        self.contacts.push(contact)
                    }
                    self.selectedContact = contact
                    self.loadMessages(contact.id)
                }
            }).catch(function() {
                toast('会话加载失败', 2)
            })
        },

        sendMessage() {
            var self = this
            if (!self.messageInput.trim()) return
            var contact = self.selectedContact
            if (!contact) return

            if (!contact.canSendUnlimited) {
                var sentCount = 0
                if (contact.messages) {
                    sentCount = contact.messages.filter(function(m) { return m.isSelf }).length
                }
                if (sentCount >= 1) {
                    toast('对方未关注你，在回复前你最多只能发送1条消息', 2)
                    return
                }
            }

            var content = self.messageInput.trim()
            var url = self.getNotificationUrl('im_send')
            request.post(url, { receiver_id: contact.peerId, content: content, msg_type: 1 }, {}).then(function(d) {
                if (d && d.code === 200) {
                    var newMsg = {
                        id: d.data.message_id,
                        content: content,
                        time: Date.now(),
                        isSelf: true,
                        status: 0
                    }
                    if (!contact.messages) {
                        self.$set(contact, 'messages', [])
                    }
                    contact.messages.push(newMsg)
                    self.$set(contact, 'lastMessage', content)
                    self.$set(contact, 'lastTime', Date.now())
                    self.$set(contact, 'sentCount', (contact.sentCount || 0) + 1)
                    self.messageInput = ''
                    self.$nextTick(function() {
                        var el = self.$refs.chatMessages
                        if (el) el.scrollTop = el.scrollHeight
                    })
                } else {
                    var msg = (d && d.errorMessage) || '发送失败'
                    if (d && d.code === 403) {
                        msg = '由于对方并未关注你，在收到对方回复之前，你最多只能发送1条文字消息'
                    }
                    toast(msg, 2)
                }
            }).catch(function() {
                toast('发送失败，请检查网络', 2)
            })
        },

        onScroll(e, type) {
            var el = e.target
            if (el.scrollHeight - el.scrollTop - el.clientHeight < 100) {
                this.loadMoreNotifications(type)
            }
        },

        getSystemTypeLabel(type) {
            var labels = {
                'activity': '活动',
                'system': '系统'
            }
            return labels[type] || '系统'
        },
        getSystemIcon(type) {
            // 返回 FontAwesome 图标类名
            if (type === 'activity') return 'fa-bullhorn'
            return 'fa-bell'
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.notification-page {
    min-height: 100vh;
    background: #f7f8fa;
}

.notification-content {
    max-width: 900px;
    margin: 0 auto;
    padding: 24px;
}

.tabs-bar {
    display: flex;
    background: #fff;
    border-radius: 8px;
    padding: 0 16px;
    margin-bottom: 16px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.tab-item {
    padding: 16px 24px;
    font-size: 14px;
    color: #515767;
    cursor: pointer;
    position: relative;
    transition: color 0.2s;
    &:hover {
        color: #1e80ff;
    }
    &.active {
        color: #1e80ff;
        &::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 50%;
            transform: translateX(-50%);
            width: 24px;
            height: 3px;
            background: #1e80ff;
            border-radius: 2px;
        }
    }
}

.content-area {
    background: #fff;
    border-radius: 8px;
    padding: 16px;
    min-height: 400px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.tab-content {
    overflow-y: auto;
    max-height: calc(100vh - 200px);
}

.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 60px 20px;
}

.empty-img {
    width: 120px;
    height: 120px;
    margin-bottom: 16px;
    border-radius: 8px;
}

.empty-text {
    font-size: 14px;
    color: #8a919f;
}

.notification-list {
    padding: 8px 0;
}

.notification-item {
    display: flex;
    gap: 12px;
    padding: 16px 0;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.notify-avatar {
    width: 48px;
    height: 48px;
    border-radius: 50%;
    object-fit: cover;
    flex-shrink: 0;
}

.notify-content {
    flex: 1;
}

.notify-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
}

.notify-user {
    font-size: 14px;
    font-weight: 500;
    color: #252933;
}

.notify-action {
    font-size: 14px;
    color: #515767;
}

.notify-time {
    font-size: 12px;
    color: #8a919f;
    margin-left: auto;
}

.notify-text {
    font-size: 14px;
    color: #252933;
    line-height: 1.5;
    margin-bottom: 4px;
}

.notify-article {
    font-size: 13px;
    color: #1e80ff;
    cursor: pointer;
    &:hover {
        text-decoration: underline;
    }
}

.notify-actions {
    display: flex;
    gap: 16px;
    margin-top: 12px;
}

.action-btn {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 4px 8px;
    border: none;
    background: transparent;
    font-size: 13px;
    color: #8a919f;
    cursor: pointer;
    transition: color 0.2s;
    &:hover {
        color: #1e80ff;
    }
    &.active {
        color: #ff4d4f;
    }
}

.action-icon {
    font-family: fontawesome;
    font-size: 14px;
}

.action-text {
    font-size: 13px;
}

.follow-btn {
    padding: 6px 16px;
    border: 1px solid #e4e6eb;
    border-radius: 4px;
    background: #fff;
    color: #515767;
    font-size: 13px;
    cursor: pointer;
    flex-shrink: 0;
    &.active {
        background: #8a919f;
        color: #fff;
        border-color: #8a919f;
    }
    &:hover:not(.active) {
        border-color: #1e80ff;
        color: #1e80ff;
    }
}

.message-layout {
    display: flex;
    height: 500px;
    border: 1px solid #f2f3f5;
    border-radius: 8px;
    overflow: hidden;
}

.message-sidebar {
    width: 280px;
    border-right: 1px solid #f2f3f5;
    display: flex;
    flex-direction: column;
}

.search-box {
    padding: 12px;
    border-bottom: 1px solid #f2f3f5;
}

.search-input {
    width: 100%;
    padding: 8px 12px;
    border: 1px solid #e4e6eb;
    border-radius: 20px;
    font-size: 13px;
    outline: none;
    &:focus {
        border-color: #1e80ff;
    }
}

.contact-list {
    flex: 1;
    overflow-y: auto;
}

.contact-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px;
    cursor: pointer;
    transition: background-color 0.2s;
    &:hover {
        background: #f7f8fa;
    }
    &.active {
        background: #eaf2ff;
    }
}

.contact-avatar {
    width: 44px;
    height: 44px;
    border-radius: 50%;
    object-fit: cover;
}

.contact-info {
    flex: 1;
    min-width: 0;
}

.contact-name {
    font-size: 14px;
    color: #252933;
    margin-bottom: 2px;
}

.contact-last-message {
    font-size: 12px;
    color: #8a919f;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.contact-time {
    font-size: 12px;
    color: #c4c9d1;
}

.message-chat {
    flex: 1;
    display: flex;
    flex-direction: column;
}

.empty-chat {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
}

.empty-icon {
    font-size: 48px;
    margin-bottom: 16px;
}

.chat-content {
    display: flex;
    flex-direction: column;
    height: 100%;
}

.chat-header {
    padding: 16px;
    border-bottom: 1px solid #f2f3f5;
    background: #f7f8fa;
}

.chat-title {
    font-size: 15px;
    font-weight: 600;
    color: #252933;
}

.chat-messages {
    flex: 1;
    padding: 16px;
    overflow-y: auto;
}

.message-item {
    display: flex;
    gap: 12px;
    margin-bottom: 16px;
    &.is-self {
        flex-direction: row-reverse;
        .msg-content {
            align-items: flex-end;
            .msg-text {
                background: #1e80ff;
                color: #fff;
                border-radius: 12px 12px 0 12px;
            }
        }
    }
}

.msg-avatar {
    width: 36px;
    height: 36px;
    border-radius: 50%;
    object-fit: cover;
    flex-shrink: 0;
}

.msg-content {
    display: flex;
    flex-direction: column;
    gap: 4px;
    max-width: 60%;
}

.msg-text {
    padding: 10px 14px;
    background: #f7f8fa;
    border-radius: 12px 12px 12px 0;
    font-size: 14px;
    line-height: 1.5;
    word-break: break-all;
}

.msg-time {
    font-size: 12px;
    color: #8a919f;
}

.chat-warning {
    padding: 8px 16px;
    background: #fff7e6;
    color: #fa8c16;
    font-size: 12px;
    text-align: center;
}

.chat-input-area {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 16px;
    border-top: 1px solid #f2f3f5;
    background: #fff;
}

.input-icon-btn {
    width: 36px;
    height: 36px;
    border: none;
    background: transparent;
    font-size: 20px;
    cursor: pointer;
    border-radius: 50%;
    &:hover {
        background: #f7f8fa;
    }
}

.chat-input {
    flex: 1;
    padding: 10px 14px;
    border: 1px solid #e4e6eb;
    border-radius: 20px;
    font-size: 14px;
    outline: none;
    &:focus {
        border-color: #1e80ff;
    }
}

.send-btn {
    padding: 10px 24px;
    border: none;
    border-radius: 20px;
    background: #1e80ff;
    color: #fff;
    font-size: 14px;
    cursor: pointer;
    &:hover {
        background: #4096ff;
    }
}

.system-content {
    padding-left: 0;
    display: flex;
    align-items: flex-start;
    gap: 16px;
}

.system-icon-wrap {
    flex-shrink: 0;
    width: 44px;
    height: 44px;
    border-radius: 10px;
    background: linear-gradient(135deg, #4096ff 0%, #1e80ff 100%);
    display: flex;
    align-items: center;
    justify-content: center;
}

.system-icon {
    color: #fff;
    font-size: 20px;
}

.system-main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
}

.system-preview {
    flex-shrink: 0;
    width: 140px;
    padding: 8px 10px;
    background: #f5f7fa;
    border-radius: 8px;
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.preview-text {
    font-size: 13px;
    color: #515767;
    line-height: 1.4;
    display: -webkit-box;
    -webkit-line-clamp: 1;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.preview-tag {
    font-size: 11px;
    color: #1e80ff;
    background: #e8f3ff;
    padding: 1px 6px;
    border-radius: 3px;
    align-self: flex-start;
}

.system-type-tag {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 12px;
    font-weight: 500;
    margin-right: 8px;
    &.tag-activity {
        background: #fff0e6;
        color: #fa8c16;
    }
    &.tag-system {
        background: #e8f3ff;
        color: #1e80ff;
    }
}

.notify-link {
    display: inline-block;
    margin-top: 8px;
    font-size: 13px;
    color: #1e80ff;
    text-decoration: none;
    &:hover {
        text-decoration: underline;
    }
}

@media screen and (max-width: 768px) {
    .message-layout {
        flex-direction: column;
        height: auto;
    }
    .message-sidebar {
        width: 100%;
        border-right: none;
        border-bottom: 1px solid #f2f3f5;
        max-height: 200px;
    }
}
</style>