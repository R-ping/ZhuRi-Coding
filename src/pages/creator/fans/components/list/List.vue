<template>
  <div>
    <ul class="list">
      <li v-for="item in fanList" :key="item.id">
        <img class="article-img" :src="item.photo || defaultAvatar">
        <div class="fan-name">{{item.fans_name}}</div>
        <el-button type="primary" size="small" v-if="!item.is_follow" @click="followFan(item.fans_id, $event)">关注</el-button>
        <el-button type="warning" size="small" v-if="item.is_follow" @click="cancleFollowFan(item.fans_id, $event)">取消关注</el-button>
      </li>
      <!--因为目前的接口没有数据 所以为了体验 这里采用模拟的数据-->
      <!--    <div v-if="!fansList || !fansList.length">
            <li v-for="(item,index) in list" :key="index">
             <img class="article-img" src="@/assets/avatar.jpg">
             <div>测试粉丝</div>
             <el-button type="primary">关注</el-button>
            </li>
          </div>-->
    </ul>
    <div class="pagination">
      <el-pagination
        layout="total,prev, pager, next"
        @current-change='pageChange'
        :current-page.sync='listPage.currentPage'
        :page-size="pageSize"
        :total="total">
      </el-pagination>
    </div>
  </div>
</template>

<script>
const defaultAvatar = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'
export default {
  data() {
    return {
       list:[1,2,3,4,5,6,7,8,8,9,9],
       listPage:{
         currentPage:1
       },
       defaultAvatar
    }
  },
  props: ["name", "fanList", "total", "pageSize", "changePage", "followOperate"],
  components: {
  },
  computed: {

  },
  methods: {
    pageChange: function (newPage) {
      this.changePage && this.changePage({page: newPage})
    },
    cancleFollowFan: function (fanId, event) {
      this.followOperate && this.followOperate({fans_id: fanId, switch_state: false})
    },
    followFan: function (fanId, event) {
      this.followOperate && this.followOperate({fans_id: fanId, switch_state: true})
    }
  }
}
</script>

<style rel="stylesheet/less" lang="less" scoped>
@import '../../../layout/styles/variables.less';
  .list {
    overflow: hidden;
    padding: 0;
    margin: 0 -10px;
    li {
      background-color: #ffffff;
      border: 1px solid #e8e8e8;
      border-radius: @cardRadius;
      box-shadow: @cardShadow;
      width: calc(20% - 20px);
      float: left;
      margin: 10px;
      text-align: center;
      padding: 24px 0;
      img {
        border-radius: 50%;
        width: 80px;
        height: 80px;
      }
      .fan-name {
        font-size: 14px;
        color: @textPrimary;
        margin: 12px 0;
        font-weight: 500;
      }
    }
  }
  .pagination {
    margin-top: 24px;
    text-align: right;
  }
</style>
