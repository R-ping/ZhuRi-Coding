<template>
    <div class="login-overlay">
        <div class="login-modal">
            <div class="modal-header">
                <span class="modal-title">登录</span>
                <span class="close-btn" @click="goHome">&#10005;</span>
            </div>
            <div class="modal-body">
                <div class="form-section">
                    <div class="form-wrapper">
                        <div class="input-group">
                            <span class="area-code">+86</span>
                            <input v-model="params.phone" 
                                   placeholder="请输入手机号"
                                   class="input"
                            />
                        </div>
                        <div class="input-group">
                            <input v-model="params.password" 
                                   type="password"
                                   placeholder="请输入密码"
                                   class="input"
                            />
                        </div>
                        <span class="button" @click="login">登录</span>
                    </div>
                    <div class="link-row">
                        <span class="go-register" @click="tip">手机号注册</span>
                        <router-link to="/home">
                            <span class="go-home">不登录，先看看</span>
                        </router-link>
                    </div>
                    <div class="divider-wrap">
                        <span class="divider-line"></span>
                        <span class="divider-text">其他登录方式</span>
                        <span class="divider-line"></span>
                    </div>
                    <div class="third-party">
                        <span class="third-item">{{weiboIcon}}</span>
                        <span class="third-item">{{wechatIcon}}</span>
                        <span class="third-item">{{qqIcon}}</span>
                    </div>
                </div>
                <div class="qr-section">
                    <div class="qr-header">
                        <span class="qr-title">扫码登录</span>
                        <span class="qr-hint">iOS 4.1及以上版本支持</span>
                    </div>
                    <div class="qr-code">
                        <div class="qr-placeholder">
                            <span class="qr-icon">{{qrIcon}}</span>
                        </div>
                    </div>
                    <div class="qr-tip">
                        <span>打开逐日Coding APP</span>
                        <span>点击"我的-扫一扫"登录</span>
                    </div>
                </div>
            </div>
            <div class="modal-footer">
                <span class="privacy-text">
                    注册登录即表示同意
                    <span class="link">用户协议</span>
                    和
                    <span class="link">隐私政策</span>
                </span>
            </div>
        </div>
    </div>
</template>

<script>
    import Api from '@/apis/login/api'
    import { toast } from "@/utils/toast"
    export default {
        name: "login",
        data(){
            return{
                weiboIcon : '\uf18a',
                wechatIcon : '\uf1d7',
                qqIcon : '\uf1d6',
                qrIcon : '\uf029',
                params:{
                    phone:'',
                    password:''
                }
            }
        },
        methods:{
            tip : function(){
                toast('该功能暂未实现！', 3)
            },
            goHome: function(){
                this.$router.push("/home")
            },
            login:function(){
                if(this.params.phone==''||this.params.password==''){
                    toast('请输入用户名或密码', 3)
                }else{
                    Api.login(this.params).then(d=>{
                        if(d.code === 200 && d.data && d.data.accessToken){
                            this.$store.dispatch('login', d.data)
                            this.$router.push("/home")
                        }else{
                            toast(d.errorMessage || '用户或密码错误', 3)
                        }
                    }).catch(e=>{
                    })
                }
            }
        }
    }
</script>

<style lang="less" scoped>
    @import '../../styles/common';
    .login-overlay {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background-color: rgba(0, 0, 0, 0.5);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
    }
    .login-modal {
        width: 90%;
        max-width: 880px;
        background-color: #ffffff;
        border-radius: 12px;
        overflow: hidden;
        box-shadow: 0 10px 30px rgba(0, 0, 0, 0.2);
    }
    .modal-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 30px 40px;
        border-bottom: 1px solid #f0f0f0;
    }
    .modal-title {
        font-size: 24px;
        color: #333333;
        font-weight: bold;
    }
    .close-btn {
        width: 36px;
        height: 36px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 24px;
        color: #999999;
        cursor: pointer;
    }
    .close-btn:hover {
        color: #666666;
    }
    .modal-body {
        display: flex;
        padding: 40px;
        gap: 50px;
    }
    .form-section {
        flex: 1;
        min-width: 320px;
    }
    .form-wrapper {
        display: flex;
        flex-direction: column;
        gap: 20px;
    }
    .input-group {
        display: flex;
        align-items: center;
        background-color: #fafafa;
        border-radius: 8px;
        padding: 0 20px;
        border: 1px solid #f0f0f0;
        transition: border-color 0.3s;
    }
    .input-group:focus-within {
        border-color: #3194ff;
    }
    .area-code {
        font-size: 18px;
        color: #666666;
        padding-right: 16px;
        border-right: 1px solid #e0e0e0;
        margin-right: 16px;
    }
    .input {
        flex: 1;
        height: 54px;
        line-height: 54px;
        font-size: 18px;
        color: #333333;
        background-color: transparent;
        border: none;
        outline: none;
    }
    .button {
        height: 54px;
        line-height: 54px;
        background-color: #3194ff;
        color: #ffffff;
        font-size: 18px;
        text-align: center;
        border-radius: 8px;
        margin-top: 10px;
        cursor: pointer;
        transition: background-color 0.3s;
    }
    .button:hover {
        background-color: #1a7de8;
    }
    .link-row {
        display: flex;
        justify-content: space-between;
        margin-top: 20px;
    }
    .go-register, .go-home {
        font-size: 16px;
        color: #999999;
        cursor: pointer;
    }
    .go-register:hover, .go-home:hover {
        color: #3194ff;
    }
    .divider-wrap {
        display: flex;
        align-items: center;
        margin-top: 40px;
        padding: 0 15px;
    }
    .divider-line {
        flex: 1;
        height: 1px;
        background-color: #e0e0e0;
    }
    .divider-text {
        padding: 0 20px;
        font-size: 16px;
        color: #cccccc;
    }
    .third-party {
        display: flex;
        justify-content: center;
        gap: 50px;
        margin-top: 32px;
    }
    .third-item {
        width: 52px;
        height: 52px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 28px;
        color: #666666;
        font-family: fontawesome;
        cursor: pointer;
        transition: color 0.3s;
    }
    .third-item:hover {
        color: #3194ff;
    }
    .qr-section {
        display: none;
        flex-direction: column;
        align-items: center;
        padding: 20px 30px;
        border-left: 1px solid #f0f0f0;
    }
    .qr-header {
        text-align: center;
        margin-bottom: 20px;
    }
    .qr-title {
        display: block;
        font-size: 18px;
        color: #333333;
        font-weight: bold;
        margin-bottom: 8px;
    }
    .qr-hint {
        font-size: 14px;
        color: #999999;
    }
    .qr-code {
        width: 180px;
        height: 180px;
        background-color: #ffffff;
        border: 1px solid #e0e0e0;
        border-radius: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
        margin-bottom: 16px;
    }
    .qr-placeholder {
        width: 150px;
        height: 150px;
        background-color: #f8f8f8;
        border-radius: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
    }
    .qr-icon {
        font-size: 70px;
        color: #cccccc;
        font-family: fontawesome;
    }
    .qr-tip {
        text-align: center;
        font-size: 14px;
        color: #999999;
        line-height: 1.8;
    }
    .modal-footer {
        padding: 25px 40px;
        border-top: 1px solid #f0f0f0;
    }
    .privacy-text {
        display: block;
        text-align: center;
        font-size: 14px;
        color: #cccccc;
        line-height: 1.8;
    }
    .link {
        color: #3194ff;
        cursor: pointer;
    }
    @media screen and (min-width: 600px) {
        .qr-section {
            display: flex;
        }
    }
    @media screen and (max-width: 600px) {
        .login-overlay {
            background-color: #ffffff;
        }
        .login-modal {
            width: 100%;
            max-width: 100%;
            border-radius: 0;
            box-shadow: none;
            min-height: 100vh;
        }
        .modal-header {
            padding: 20px;
        }
        .modal-title {
            font-size: 18px;
        }
        .modal-body {
            padding: 20px;
            flex-direction: column;
            gap: 20px;
        }
        .input {
            height: 50px;
            line-height: 50px;
            font-size: 17px;
        }
        .button {
            height: 50px;
            line-height: 50px;
            font-size: 17px;
        }
        .go-register, .go-home {
            font-size: 15px;
        }
        .third-item {
            width: 50px;
            height: 50px;
            font-size: 28px;
        }
    }
</style>