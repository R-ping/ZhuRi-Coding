<template>
    <div class="list-item">
        <div class="list-lr">
            <div class="item-l">
                <span class="title" v-html="safeTitle"></span>
                <div class="tag-list" v-if="data.tags && data.tags.length">
                    <span class="tag-item" v-for="tag in data.tags" :key="tag">{{tag}}</span>
                </div>
                <div class="tags">
                    <img v-if="data.authorImage" class="author-avatar" :src="data.authorImage" alt="作者头像" loading="lazy" @mouseenter="onAuthorHover($event)" @mouseleave="onAuthorLeave" @click.stop="onAuthorClick"/>
                    <span class="tags-text tags-icon">{{data.icon}}</span>
                    <span class="author-link" @mouseenter="onAuthorHover($event)" @mouseleave="onAuthorLeave" @click.stop="onAuthorClick">{{data.source}}</span>
                    <span class="meta-sep">·</span>
                    <span class="tags-text meta-comment">{{data.comment}} 评论</span>
                    <span class="meta-sep">·</span>
                    <span class="tags-text meta-comment">{{data.views}} 阅读</span>
                    <span class="meta-sep">·</span>
                    <span class="tags-text meta-like"><i class="like-icon">&#xf087;</i> {{data.likes}} 点赞</span>
                    <span class="tags-text date" v-if="showTime">{{formatTime(data.date)}}</span>
                </div>
            </div>
            <div class="item-r" v-if="data.coverImage">
                <img class="image" :src="data.coverImage" loading="lazy"/>
            </div>
        </div>
    </div>
</template>

<script>
    import { sanitizeHighlight } from '../../utils/sanitize.js'

    export default {
        name: "article_0",
        props:{
            data:{
                type:Object
            },
            // 是否显示发布时间（最新分栏为 true，推荐分栏为 false）
            showTime:{
                type:Boolean,
                default:false
            }
        },
        computed: {
            safeTitle() {
                return sanitizeHighlight(this.data.title || '')
            }
        },
        methods: {
            formatTime:function(time){
                if (!time) return ''
                var diff = Date.now() - time
                if (diff < 0) return '刚刚'
                var minutes = Math.floor(diff / 60000)
                var hours = Math.floor(diff / 3600000)
                var days = Math.floor(diff / 86400000)
                var months = Math.floor(diff / 2592000000)
                if (minutes < 1) return '刚刚'
                if (minutes < 60) return minutes + '分钟前'
                if (hours < 24) return hours + '小时前'
                if (days < 30) return days + '天前'
                if (months < 12) return months + '个月前'
                return Math.floor(months / 12) + '年前'
            },
            // 作者昵称悬浮 -> 通知父级展示作者信息卡片
            onAuthorHover:function(event){
                this.$emit('author-hover', { userId: this.data.authorId, event: event })
            },
            onAuthorLeave:function(){
                this.$emit('author-leave')
            },
            // 点击作者头像/昵称 -> 跳转目标用户个人主页
            onAuthorClick:function(){
                this.$emit('author-click', this.data.authorId)
            }
        }
    }
</script>

<style lang="less" scoped>
    @import '../../styles/article';
    .list-lr{
        display: flex;
        flex-direction: row;
        justify-content: space-between;
        align-items: flex-start;
        gap: 16px;
    }
    .item-l{
        flex: 1;
        min-width: 0;
    }
    .item-r{
        width: 120px;
        flex-shrink: 0;
    }
    .item-r .image {
        width: 120px;
        height: 80px;
    }
    .title {
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
        font-weight: 600;
    }
    .title /deep/ font {
        color: #ff0000;
    }
    .meta-sep {
        color: #c0c4cc;
        font-size: 12px;
        margin: 0 4px;
        user-select: none;
    }
    .meta-comment {
        color: #b0b5c0;
    }
    .tag-list {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        margin: 6px 0 0;
    }
    .tag-item {
        font-size: 12px;
        color: #1E80FF;
        background: rgba(30, 128, 255, 0.08);
        border-radius: 4px;
        padding: 2px 8px;
        line-height: 1.4;
    }
    .like-icon {
        font-family: fontawesome;
        font-style: normal;
        font-size: 12px;
        margin-right: 2px;
    }
    .meta-like {
        color: #b0b5c0;
    }
    .author-avatar {
        width: 20px;
        height: 20px;
        border-radius: 50%;
        margin-right: 6px;
        vertical-align: -4px;
        flex-shrink: 0;
    }
</style>