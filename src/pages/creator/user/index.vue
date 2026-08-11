<template>
  <div class="user-page">
    <div class="user-card">
      <header class="card-header">账号信息</header>
      <div class="card-body">
        <div class="form-item username">
          <label>
            <div>
            <img :src="headImg" class="user-avatar" /><br />
            <a @click="showHead" href="javascript:;">更换头像</a>
            </div>
          </label>
          <div v-if="!editUser" class="rightContent">
            <dl>
              <dt>{{user.name}}</dt>
              <dd>{{user.intro}}</dd>
            </dl>
              <a @click="beginEdit('user')" href="javascript:;">修改</a>
          </div>
             <div v-if="editUser" class="clause edituser">
               <el-form label-position="top">
                 <el-form-item label="名称">
                    <el-input placeholder="请输入头条号名称" v-model="userData.name"></el-input>
                 </el-form-item>
                  <el-form-item label="简介">
                    <el-input placeholder="请输入头条号简介" v-model="userData.intro"></el-input>
                 </el-form-item>
               </el-form>
              <div class="btn-group">
                <el-button @click="saveEdit('user')" type="primary">保存</el-button>
                <el-button @click="cancelEdit('user')">取消</el-button>
              </div>
            </div>
        </div>
        <div class="form-item userinfo">
          <label>账号信息</label>
          <div class="rightContent">
            <div class="clause">
              <span>头条号类型</span>个人
            </div>
            <div class="clause">
              <span>头条号ID</span>{{user.id}}
            </div>
          </div>
        </div>
        <div class="form-item userinfo">
          <label>登录方式</label>
          <div class="rightContent">
            <div  class="clause">
              <span>绑定手机</span>{{user.mobile}}
            </div>
          </div>
        </div>
        <div class='form-item userinfo'>
            <label>邮箱</label>
            <div class="rightContent">
            <div v-if="!editEmail" class="clause">
               <span>{{user.email}}</span>
               <a href="javascript:;" @click="beginEdit('email')">修改邮箱</a>
            </div>
            <div v-if="editEmail" class="clause edit-email">
              <el-form label-position="top">
                <el-form-item label="邮箱">
                  <el-input placeholder="请输入邮箱地址" v-model="emailData"></el-input>
                </el-form-item>
              </el-form>
              <div class="btn-group">
                <el-button @click="saveEdit('email')" type="primary">保存</el-button>
                <el-button @click="cancelEdit('email')">取消</el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
    <el-dialog
      :visible.sync="showHeadUpload"
       title="上传头像"
       width="480px"
      >
      <div class="avatar-upload-wrap">
        <img  class='localimg' v-if="showLocalImg" :src="localImg" alt="">
        <el-upload  :on-change="fileChange" ref="myUpload" class="avatar-uploader" :auto-upload="false" :limit="1">
           <i  class="el-icon-plus avatar-uploader-icon"></i>
         </el-upload>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="showHeadUpload = false">取 消</el-button>
        <el-button type="primary" @click="uploadHead">确 定</el-button>
     </span>
    </el-dialog>
  </div>
</template>

<script>
import { getUserProfile , updateUserProfile , updateUserHead } from '@/apis/creator/user'
import { setUser } from '../utils/store'
const defaultAvatar = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'
export default {
  name: 'ContentManage',
  data() {
    return {
       user:{},
       emailData:null, // 邮箱
       userData:null,  // 用户信息
       editEmail:false,// 编辑邮箱
       editUser:false, // 编辑用户信息
       showHeadUpload:false,
       showLocalImg:false, // 显示本地图片
       localImg:null
    }
  },
  created () {
     this.getUser() // 获取用户个人资料
  },
  computed: {
     headImg () {
        return  this.user.photo ? this.user.photo : defaultAvatar
     }
  },
  methods: {
    fileChange () {
         let file = document.querySelector('.el-upload .el-upload__input').files[0] ;
         this.localImg = URL.createObjectURL(file)
         this.showLocalImg = true // 显示图片
    },
    /***
     * 显示上传头像的图层
     * ***/
    showHead () {
      this.$refs.myUpload && this.$refs.myUpload.clearFiles()  // 清除垃圾数据
      this.showHeadUpload = true // 显示弹层
    },
    async getUser () {
      let  result = await getUserProfile()  // 获取用户数据
      setUser(result) // 更新数据到缓存中
      this.user = result;  // 设置用户数据
    },
    // 开始进入编辑态 编辑的是某个信息
    beginEdit (type) {
       if(this.checkOtherClose(type)) {
            this.$message({type:'warning',message:'请关掉其他正在编辑的内容'})
            return;
       }
       if(type == 'email') {
          this.editEmail = true // 编辑邮箱
          this.emailData = this.user.email // 读取邮箱
       }
       else if(type == 'user') {
          this.userData = {...this.user} // 解构方式的赋值 因为如果是对象 可能造成数据会自动同步到原有数据
          this.editUser = true // 用户信息
       }
    },
    // 检查其他的编辑状态功能是否已关闭
    checkOtherClose (type) {
        if(type == 'email'){
          return this.editUser
       }
       else if(type == 'user'){
          return  this.editEmail
       }
    },
    // 取消编辑 通用方法
    cancelEdit (type) {
        if(type == 'email') {
          this.editEmail = false // 取消编辑邮箱
       }
       else if(type == 'user') {
          this.editUser = false // 取消编辑用户
       }
    },
    // 保存编辑态
  async saveEdit (type) {
      if(type == 'email') {
        let pattrn = /^[A-Za-z0-9._%-]+@([A-Za-z0-9-]+\.)+[A-Za-z]{2,4}$/
        if(this.emailData.match(pattrn)) {
         await updateUserProfile({...this.user,email:this.emailData})
         this.getUser () // 重新加载数据
        this.editEmail = false  // 保存成功关闭
        }else{
           this.$message({ type:'warning',message:'邮箱格式不正确!'})
        }
     }
     else if (type == 'user') {
        if(this.userData.name){
            await updateUserProfile(this.userData)
            this.getUser () // 重新加载数据
            this.editUser = false  // 保存成功关闭
        }else{
           this.$message({type:'warning', message:'头条号名称不能为空' })
        }
     }
    },
    // 修改头像
   async uploadHead () {
        let files = document.querySelector('.el-upload .el-upload__input').files ;
        if(files && files.length) {
          let fd = new FormData();
          fd.append('photo', files[0], files[0].name);
           await updateUserHead(fd)
          this.$message({message:'上传成功',type:'success'}) && this.getUser () // 重新加载数据
        }else{
           this.$message({message:"请选择一张图片",type:"warning"})
        }
    }
  }
}
</script>
<style rel="stylesheet/less" lang="less" scoped>
@import '../layout/styles/variables.less';
.user-page {
  min-height: calc(100vh - 70px);
  background-color: @bgGray;
  padding: 20px;
  display: flex;
  justify-content: center;
  .user-card {
    background-color: #ffffff;
    border-radius: @cardRadius;
    box-shadow: @cardShadow;
    width: 100%;
    max-width: 800px;
    height: fit-content;
    .card-header {
      padding: 0 24px;
      height: 56px;
      line-height: 56px;
      font-size: 16px;
      color: @textPrimary;
      border-bottom: 1px solid #e8e8e8;
    }
    .card-body {
      padding: 24px 32px 48px;
    }
    .avatar-upload-wrap {
      position: relative;
      display: flex;
      justify-content: center;
      padding: 20px 0;
    }
    .avatar-uploader  {
      border: 1px dashed #d9d9d9;
      border-radius: 8px;
      cursor: pointer;
      position: relative;
      width: 220px;
      height: 220px;
      overflow: hidden;
    }
    .localimg {
      width: 220px;
      height: 220px;
      border-radius: 8px;
      position: absolute;
      object-fit: cover;
      z-index: 1;
    }
    .avatar-uploader:hover {
      border-color: @brandBlue;
    }
     .avatar-uploader-icon {
      font-size: 36px;
      color: #8c939d;
      width: 220px;
      height: 220px;
      line-height: 220px;
      text-align: center;
    }
    .form-item {
      display: flex;
      border-bottom: 1px solid #e8e8e8;
      padding: 28px 0;
      font-size: 14px;
      color: @textSecondary;
      &:last-child {
        border: none;
      }
      label {
        width: 100px;
        text-align: right;
        font-weight: normal;
        color: @textPrimary;
        flex-shrink: 0;
        margin-right: 24px;
        img {
          width: 80px;
          height: 80px;
          border-radius: 50%;
          object-fit: cover;
        }
        a {
          color: @brandBlue;
          font-size: 13px;
        }
      }
      .rightContent {
        flex: 1;
        min-width: 0;
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        a {
          color: @brandBlue;
          font-size: 13px;
          white-space: nowrap;
        }
      }
    }
    .username {
      align-items: flex-start;
      .edituser {
        flex: 1;
        min-width: 0;
        .el-form-item {
          margin-bottom: 18px;
        }
        .el-input {
          width: 100%;
        }
        .btn-group {
          margin-top: 8px;
        }
      }
      .rightContent {
        margin-top: 8px;
        dl {
          dt {
            font-size: 18px;
            color: @textPrimary;
          }
          dd {
            color: @textSecondary;
            font-size: 14px;
            margin-top: 8px;
          }
        }
      }
    }
    .userinfo {
      align-items: baseline;
      .rightContent {
        display: block;
      }
      .clause {
        display: flex;
        align-items: center;
        min-height: 40px;
        margin-bottom: 12px;
        &:last-child {
          margin-bottom: 0;
        }
        a {
          margin-left: auto;
        }
        span {
          color: @textPrimary;
          width: 100px;
          flex-shrink: 0;
        }
      }
      .edit-email {
        display: block;
        .el-form-item {
          margin-bottom: 12px;
        }
        .el-input {
          width: 100%;
        }
      }
    }
    .el-input {
      width: 100%;
    }
  }
}
</style>
