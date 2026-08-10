<template>
    <div class="list-item">
        <span class="title" v-html="safeTitle"></span>
        <div class="tags">
            <span class="tags-text tags-icon">{{data.icon}}</span>
            <span class="tags-text">{{data.source}}</span>
            <span class="meta-sep">·</span>
            <span class="tags-text meta-comment">{{data.comment}} 评论</span>
            <span class="tags-text date">{{formatDate(data.date)}}</span>
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
            }
        },
        computed: {
            safeTitle() {
                return sanitizeHighlight(this.data.title || '')
            }
        },
        methods: {
            formatDate: function(time) {
                return this.$date.format13(time);
            }
        }
    }
</script>

<style lang="less" scoped>
    @import '../../styles/article';
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
</style>