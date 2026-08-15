<template>
    <div class="creator-dropdown" @click.stop>
        <div class="dropdown-menu-grid">
            <div class="dropdown-item" @click="handleNavigate('/creator/publish', true)">
                <div class="dropdown-icon-box">
                    <span class="dropdown-icon">&#xf040;</span>
                </div>
                <span class="dropdown-label">写文章</span>
            </div>
            <div class="dropdown-item" @click="handleNavigate('/pins')">
                <div class="dropdown-icon-box">
                    <span class="dropdown-icon">&#xf142;</span>
                </div>
                <span class="dropdown-label">发沸点</span>
            </div>
            <div class="dropdown-item" @click="handleNavigate('/creator/content?tab=draft')">
                <div class="dropdown-icon-box">
                    <span class="dropdown-icon">&#xf0f6;</span>
                </div>
                <span class="dropdown-label">草稿箱</span>
            </div>
            <div class="dropdown-item" :class="{ disabled: !coursePermission.hasPermission }" @click="handleCourseClick">
                <div class="dropdown-icon-box">
                    <span class="dropdown-icon" v-html="coursePermission.hasPermission ? '&#xf02d;' : '&#xf023;'"></span>
                </div>
                <span class="dropdown-label">写小册</span>
            </div>
        </div>
    </div>
</template>

<script>
import { permission } from '@/utils/permission'
import { toast } from '@/utils/toast'

export default {
    name: 'CreatorDropdown',
    props: {
        refreshKey: {
            type: Number,
            default: 0
        }
    },
    data() {
        return {
            coursePermission: {
                hasPermission: false,
                powerLevel: 0,
                requiredLevel: 0
            }
        }
    },
    watch: {
        refreshKey(newVal) {
            if (newVal > 0) {
                this.loadCoursePermission()
            }
        }
    },
    // v-if 条件渲染下首次挂载时 refreshKey 已是递增后的值，watch 不会触发，
    // 需在 mounted 主动加载一次课程创作权限，否则「写小册」恒为锁定
    mounted() {
        this.loadCoursePermission()
    },
    methods: {
        async loadCoursePermission() {
            try {
                const res = await permission.canCreateCourse()
                this.coursePermission = res
            } catch (e) {
                this.coursePermission = { hasPermission: false, powerLevel: 0, requiredLevel: 9 }
            }
        },
        handleNavigate(path, isNewWindow) {
            this.$emit('navigate', path, isNewWindow)
            this.$emit('close')
        },
        handleCourseClick() {
            if (!this.coursePermission.hasPermission) {
                toast('当前逐力值等级，未达到写小册要求', 2)
                return
            }
            // 写小册 -> 创作者中心课程编辑器（无 courseId 即为新建课程）
            this.handleNavigate('/creator/course/edit')
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.creator-dropdown {
    position: absolute;
    top: 100%;
    left: 0;
    margin-top: 8px;
    background-color: #ffffff;
    border-radius: 12px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
    width: 240px;
    z-index: 200;
    overflow: hidden;
    animation: fadeIn 0.2s ease;
    padding: 16px 12px 12px;
}

@keyframes fadeIn {
    from {
        opacity: 0;
        transform: translateY(-4px);
    }
    to {
        opacity: 1;
        transform: translateY(0);
    }
}

.dropdown-menu-grid {
    display: flex;
    justify-content: space-around;
    align-items: flex-start;
    gap: 4px;
}

.dropdown-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 8px 4px;
    cursor: pointer;
    transition: all 0.2s;
    flex: 1;
    min-width: 0;
    border-radius: 8px;
}

.dropdown-item:hover {
    background-color: #f5f7fa;
}

.dropdown-item.disabled {
    cursor: not-allowed;
    .dropdown-icon-box {
        background-color: #f2f3f5;
    }
    .dropdown-icon {
        color: #c0c4cc;
    }
    .dropdown-label {
        color: #c0c4cc;
    }
}

.dropdown-item.disabled:hover {
    background-color: transparent;
}

.dropdown-item.disabled:hover .dropdown-icon-box {
    background-color: #f2f3f5;
}

.dropdown-item.disabled:hover .dropdown-icon {
    color: #c0c4cc;
}

.dropdown-item.disabled:hover .dropdown-label {
    color: #c0c4cc;
}

.dropdown-icon-box {
    width: 40px;
    height: 40px;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: #f0f5ff;
    border-radius: 10px;
    margin-bottom: 8px;
    transition: background-color 0.2s;
}

.dropdown-item:hover .dropdown-icon-box {
    background-color: #e1ecff;
}

.dropdown-icon {
    font-family: fontawesome;
    font-size: 18px;
    color: @mian-color;
    transition: color 0.2s;
}

.dropdown-item:hover .dropdown-icon {
    color: #1677ff;
}

.dropdown-label {
    font-size: 13px;
    color: #333;
    white-space: nowrap;
    transition: color 0.2s;
}

.dropdown-item:hover .dropdown-label {
    color: #1677ff;
}
</style>
