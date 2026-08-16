<template>
    <div class="sidebar">
        <div class="sidebar-write-section">
            <div class="write-btn" @click="goPublish">
                <i class="el-icon-edit"></i> 写文章
            </div>
        </div>
        <el-menu class="sidebar-el-menu"
            :default-active="defaultRoute"
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
    padding: 16px;
    .write-btn {
        width: 100%;
        height: 40px;
        line-height: 40px;
        text-align: center;
        background-color: #1e80ff;
        color: #fff;
        border-radius: 6px;
        font-size: 14px;
        font-weight: 500;
        cursor: pointer;
        transition: background-color 0.2s;
        &:hover {
            background-color: #1171ee;
        }
        i {
            margin-right: 6px;
        }
    }
}

.sidebar {
    background-color: @menuBg;
    height: 100%;
}
</style>