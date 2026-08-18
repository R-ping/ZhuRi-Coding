<template>
  <div class="app-wrapper" :class="[collapse ? 'hideSidebar' : '']">
    <sidebar :collapse="collapse" class="sidebar-container"/>
    <div class="main-container">
      <navbar />
      <app-main/>
    </div>
  </div>
</template>

<script>
import 'font-awesome/css/font-awesome.min.css'
import './styles/index.less'

import { Navbar, Sidebar, AppMain } from './components'
import emitter from '../utils/event'

export default {
  name: 'CreatorLayout',
  components: {
    Navbar,
    Sidebar,
    AppMain
  },
  data () {
    return {
      collapse: true
    }
  },
  created () {
     emitter.$on('changeCollapse', () => {
         this.collapse = !this.collapse
     })
    }
}
</script>
<style lang="less">
@import './styles/variables.less';

// ===== 整体布局框架 =====
.app-wrapper {
  position: relative;
  max-width: 1400px;
  margin: 0 auto;
  min-height: 100vh;
  width: 100%;
  background-color: @bgGray;

  // 左侧固定侧边栏
  .sidebar-container {
    position: absolute;
    top: 0;
    left: 0;
    bottom: 0;
    width: @sideBarWidth;
    height: 100vh;
    z-index: 1001;
    overflow: visible;
    background-color: @menuBg;
    box-shadow: 1px 0 12px rgba(0, 0, 0, 0.06);
    border-right: 1px solid @borderLight;
    transition: width 0.3s;

    .el-scrollbar__wrap { overflow: visible !important; }
    .el-scrollbar__bar.is-vertical { display: none !important; }
    .el-scrollbar__bar.is-horizontal { display: none !important; }
    .el-submenu .el-menu { overflow: visible !important; }

    a {
      display: inline-block;
      width: 100%;
      overflow: hidden;
    }
    

    .el-submenu__title {
      font-size: 15px;
      font-weight: 600;
      color: #252933;
    }

    .el-menu {
      background-color: @menuBg;
    }
  }

  // 右侧主内容容器
  .main-container {
    margin-left: @sideBarWidth;
    min-height: 100vh;
    display: flex;
    flex-direction: column;
    transition: margin-left 0.3s;
    background-color: @bgGray;
    padding-right: 20px;
  }

  // 侧边栏收起状态
  &.hideSidebar {
    .sidebar-container {
      width: 64px !important;
    }
    .main-container {
      margin-left: 64px;
    }
    .submenu-title-noDropdown {
      padding-left: 10px !important;
      position: relative;
      .el-tooltip { padding: 0 10px !important; }
    }
    .el-submenu {
      overflow: hidden;
      & > .el-submenu__title {
        padding-left: 16px !important;
        .el-submenu__icon-arrow { display: none; }
      }
    }
    .el-menu--collapse {
      .el-submenu > .el-submenu__title > span {
        height: 0;
        width: 0;
        overflow: hidden;
        visibility: hidden;
        display: inline-block;
      }
    }
  }

  .withoutAnimation {
    .main-container,
    .sidebar-container {
      transition: none;
    }
  }
}
</style>
