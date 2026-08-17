<template>
  <div class="menu-wrapper">
      <template v-if="item.children && item.children.length > 0">
          <el-submenu 
            :index="item.title" 
            :key="item.title" 
            :class="{ 'is-locked': isGroupLocked }"
            @click.native.stop="handleGroupClick"
          >
              <template slot="title">
                <div class="group-title" :class="{ 'locked-title': isGroupLocked }">
                  <i :class="item.icon" style="margin-right: 8px; font-size: 15px;"></i>
                  {{ item.title }}
                  <i v-if="isGroupLocked" class="el-icon-lock lock-icon"></i>
                </div>
              </template>
              <template v-for="subItem in item.children">
                  <el-menu-item
                    :index="isChildLocked(subItem) ? '' : subItem.path"
                    :key="subItem.path"
                    :class="{ 'is-disabled': isChildLocked(subItem) }"
                    @click.native.stop="handleItemClick(subItem, $event)"
                  >
                    <span :class="{ 'locked-label': isChildLocked(subItem) }">{{ subItem.title }}</span>
                    <i v-if="isChildLocked(subItem)" class="el-icon-lock lock-icon-small"></i>
                  </el-menu-item>
              </template>
          </el-submenu>
      </template>
      <template v-else>
          <el-menu-item
            :index="isItemLocked ? '' : item.path"
            :key="item.path"
            :class="{ 'is-disabled': isItemLocked, 'booklet-station-item': item.bookletStation }"
            @click.native.stop="handleItemClick(item, $event)"
          >
              <i :class="item.icon"></i>
              <span slot="title" :class="{ 'locked-label': isItemLocked }">{{ item.title }}</span>
              <i v-if="isItemLocked" class="el-icon-lock lock-icon-small"></i>
              <i v-else-if="item.bookletStation" class="el-icon-right booklet-station-arrow"></i>
          </el-menu-item>
      </template>
  </div>
</template>

<script>
export default {
  name: 'SidebarItem',
  props: {
    item: {
      type: Object,
      required: true
    },
    hasCoursePermission: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    isGroupLocked() {
      return this.item.locked === true || this.item.disabled === true
    },
    isItemLocked() {
      return this.item.locked === true || this.item.disabled === true
    }
  },
  methods: {
    isChildLocked(subItem) {
      return subItem.locked === true || subItem.disabled === true
    },
    handleItemClick(clickedItem, event) {
      if (clickedItem.locked || clickedItem.disabled) {
        // 阻止事件传播和默认行为
        if (event && event.preventDefault) {
          event.preventDefault()
        }
        if (event && event.stopPropagation) {
          event.stopPropagation()
        }
        this.$emit('handle-locked', clickedItem)
        return false
      }
    },
    handleGroupClick(event) {
      if (this.isGroupLocked) {
        if (event && event.preventDefault) {
          event.preventDefault()
        }
        if (event && event.stopPropagation) {
          event.stopPropagation()
        }
        this.$emit('handle-locked', this.item)
        return false
      }
    }
  }
}
</script>

<style lang="less" scoped>
@import '../styles/variables.less';

.menu-wrapper {
    overflow: visible;
}

.group-title {
  display: inline-block;
  font-size: 15px;
  font-weight: 600;
  color: #252933;
  padding: 0;
  letter-spacing: 0;
}

.group-title.locked-title {
  color: #8a919f;
}

.menu-wrapper :deep(.el-menu-item) {
  font-size: 14px;
  color: #515767;
  padding-left: 48px !important;
}

.menu-wrapper :deep(.el-menu-item.is-active) {
  color: #1e80ff;
}

.menu-wrapper :deep(.el-menu-item.is-disabled) {
  color: #c0c4cc !important;
  cursor: not-allowed !important;
  background-color: #f5f7fa !important;
  pointer-events: none;
}

.menu-wrapper :deep(.el-submenu.is-locked > .el-submenu__title) {
  color: #8a919f !important;
}

.locked-label {
  color: #c0c4cc;
}

.lock-icon {
  font-size: 12px;
  margin-left: 4px;
  color: #c0c4cc;
}

.lock-icon-small {
  font-size: 11px;
  margin-left: 4px;
  color: #c0c4cc;
}

/* 小册站：一级栏目，样式与其它栏目区分（渐变底 + 强调色 + 箭头） */
.booklet-station-item {
  margin: 4px 8px;
  border-radius: 8px;
  background: linear-gradient(135deg, #1e80ff 0%, #4a90ff 100%);
  color: #fff !important;
  font-weight: 600;
  height: 40px;
  line-height: 40px;
}
.booklet-station-item i {
  color: #fff !important;
}
.booklet-station-item.is-active {
  background: linear-gradient(135deg, #1171ee 0%, #1e80ff 100%);
  box-shadow: 0 4px 12px rgba(30, 128, 255, 0.35);
}
.booklet-station-arrow {
  float: right;
  line-height: 40px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85) !important;
}
</style>