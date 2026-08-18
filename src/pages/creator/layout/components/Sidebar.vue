<template>
    <div class="sidebar">
        <div class="sidebar-brand">
            <img src="/static/images/logo-icon.svg" class="brand-logo" alt="逐日Coding">
            <span class="brand-text" v-if="!collapse">
                <span class="brand-name">创作中心</span>
                <span class="brand-sub">CREATOR CENTER</span>
            </span>
        </div>
        <div class="sidebar-write-section">
            <div class="write-btn" @click="goPublish">
                <i class="el-icon-edit"></i> 写文章
            </div>
        </div>
        <el-menu class="sidebar-el-menu"
            :default-active="defaultRoute"
            :default-openeds="defaultOpeneds"
             background-color="#ffffff"
             text-color="#515767"
             active-text-color="#1e80ff"
             :collapse="collapse"
            @select="handleMenuSelect"
            >
            <sidebar-item
                v-for="route in filteredItems"
                :item="route"
                :key="route.path || route.title"
                :hasCoursePermission="hasCoursePermission"
                @handle-locked="handleLockedMenu"
            />
        </el-menu>
    </div>
</template>

<script>
import SidebarItem from './SidebarItem.vue'
import { MenuData } from '../../constants/menus'
import { permission } from '@/utils/permission'
import { toast } from '@/utils/toast'

export default {
    props: ['collapse'],
    components: { SidebarItem },
    data() {
        return {
            items: MenuData,
            hasCoursePermission: false,
            permissionLoaded: false
        }
    },
    computed: {
        defaultRoute() {
            return this.$route.path
        },
        // 默认展开所有带子菜单的一级栏目（如：内容管理、数据中心、创作成长等）
        defaultOpeneds() {
            return this.filteredItems.filter(item => item.children && item.children.length > 0).map(item => item.title)
        },
        filteredItems() {
            return this.filterMenuItems(this.items)
        }
    },
    created() {
        this.loadPermissions()
    },
    methods: {
        async loadPermissions() {
            try {
                const result = await permission.canCreateCourse()
                this.hasCoursePermission = result.hasPermission
            } catch (e) {
                this.hasCoursePermission = false
            } finally {
                this.permissionLoaded = true
            }
        },
        filterMenuItems(items) {
            // 小册审核菜单仅对编辑白名单账号渲染
            const isEditor = permission.isEditor()
            const visibleItems = items.filter(item => !(item.editorOnly && !isEditor))
            return visibleItems.map(item => {
                const newItem = { ...item }
                // 检查父级是否需要权限
                const needParentPermission = item.requiredPermission === 'can_create_course'
                const isParentLocked = (needParentPermission && !this.hasCoursePermission) || item.locked
                
                if (isParentLocked) {
                    newItem.disabled = true
                    newItem.locked = true
                }
                
                if (item.children) {
                    newItem.children = item.children.map(child => {
                        const needChildPermission = child.requiredPermission === 'can_create_course'
                        const isChildLocked = isParentLocked || (needChildPermission && !this.hasCoursePermission)
                        
                        if (isChildLocked) {
                            return { 
                                ...child, 
                                disabled: true, 
                                locked: true,
                                permissionTip: child.permissionTip || item.permissionTip || '当前逐力值等级，未达到该功能要求'
                            }
                        }
                        return child
                    })
                }
                return newItem
            })
        },
        handleLockedMenu(item) {
            if (item.locked || item.disabled) {
                toast(item.permissionTip || '当前逐力值等级，未达到该功能要求')
                return
            }
        },
        handleMenuSelect(index) {
            // 检查选中的菜单项是否被锁定
            const targetItem = this.findMenuItem(index)
            if (targetItem && (targetItem.locked || targetItem.disabled)) {
                toast(targetItem.permissionTip || '当前逐力值等级，未达到该功能要求')
                return
            }
            // 手动路由跳转
            if (index && index.startsWith('/')) {
                this.$router.push(index)
            }
        },
        findMenuItem(path) {
            for (const item of this.filteredItems) {
                // 检查顶级菜单
                if (item.path === path) {
                    return item
                }
                // 检查子菜单
                if (item.children) {
                    for (const child of item.children) {
                        if (child.path === path) {
                            return child
                        }
                    }
                }
            }
            return null
        },
        goPublish() {
            window.open('/creator/publish', '_blank');
        }
    }
  }
</script>

<style lang="less" scoped>
@import '../styles/variables.less';

.sidebar-write-section {
    padding: 14px 16px 10px;
    .write-btn {
        width: 100%;
        height: 42px;
        line-height: 42px;
        text-align: center;
        background: @brandGradient;
        color: #fff;
        border-radius: 8px;
        font-size: 15px;
        font-weight: 600;
        cursor: pointer;
        letter-spacing: 1px;
        box-shadow: 0 6px 14px rgba(30, 128, 255, 0.28);
        transition: transform 0.2s ease, box-shadow 0.2s ease, filter 0.2s;
        &:hover {
            transform: translateY(-1px);
            box-shadow: 0 8px 18px rgba(30, 128, 255, 0.36);
            filter: brightness(1.05);
        }
        &:active {
            transform: translateY(0);
        }
        i {
            margin-right: 6px;
        }
    }
}

.sidebar-brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 18px 20px 4px;
    .brand-logo {
        width: 30px;
        height: 30px;
        flex-shrink: 0;
    }
    .brand-text {
        display: flex;
        flex-direction: column;
        line-height: 1.25;
        .brand-name {
            font-size: 16px;
            font-weight: 700;
            color: @textPrimary;
        }
        .brand-sub {
            font-size: 10px;
            letter-spacing: 1px;
            color: @textMuted;
        }
    }
}

.sidebar {
    background-color: @menuBg;
    height: 100%;
    display: flex;
    flex-direction: column;

    .sidebar-el-menu {
      flex: 1;
      overflow-y: auto;
    }
}
</style>