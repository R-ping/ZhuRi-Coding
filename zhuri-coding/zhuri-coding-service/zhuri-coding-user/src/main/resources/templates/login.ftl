<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>黑马头条 - 用户登录</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
        }

        .login-container {
            background: #fff;
            border-radius: 16px;
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
            width: 420px;
            padding: 40px 36px;
        }

        .login-header {
            text-align: center;
            margin-bottom: 32px;
        }

        .login-header h1 {
            font-size: 26px;
            color: #333;
            font-weight: 600;
            margin-bottom: 24px;
        }

        /* Tab切换 */
        .login-tabs {
            display: flex;
            justify-content: center;
            gap: 32px;
            margin-bottom: 32px;
        }

        .tab-item {
            font-size: 18px;
            font-weight: 600;
            cursor: pointer;
            padding: 8px 0;
            position: relative;
            transition: color 0.3s;
        }

        .tab-item.active {
            color: #ff6b6b;
        }

        .tab-item:not(.active) {
            color: #333;
        }

        .tab-item.active::after {
            content: '';
            position: absolute;
            bottom: -4px;
            left: 50%;
            transform: translateX(-50%);
            width: 32px;
            height: 3px;
            background: #ff6b6b;
            border-radius: 2px;
        }

        .login-methods {
            display: flex;
            flex-direction: column;
            gap: 16px;
        }

        /* 微信公众号区域 */
        .wechat-section {
            border: 2px solid #e8e8e8;
            border-radius: 12px;
            padding: 20px;
            text-align: center;
            transition: border-color 0.3s;
        }

        .wechat-section:hover {
            border-color: #07c160;
        }

        .wechat-qrcode {
            width: 160px;
            height: 160px;
            background: #f5f5f5;
            margin: 0 auto 12px;
            border-radius: 8px;
            display: flex;
            align-items: center;
            justify-content: center;
            color: #bbb;
            font-size: 13px;
            border: 1px dashed #ddd;
            overflow: hidden;
        }

        /* 表单区域 */
        .form-section {
            display: none;
        }

        .form-section.active {
            display: block;
        }

        .form-group {
            margin-bottom: 16px;
        }

        .form-input {
            width: 100%;
            padding: 14px 16px;
            border: 1px solid #e8e8e8;
            border-radius: 8px;
            font-size: 15px;
            outline: none;
            transition: border-color 0.3s;
            background: #f8f9fa;
        }

        .form-input:focus {
            border-color: #ff6b6b;
            background: #fff;
        }

        .password-wrapper {
            position: relative;
        }

        .password-toggle {
            position: absolute;
            right: 12px;
            top: 50%;
            transform: translateY(-50%);
            cursor: pointer;
            color: #999;
            font-size: 18px;
        }

        .login-btn {
            width: 100%;
            padding: 14px;
            background: linear-gradient(135deg, #ff6b6b 0%, #ff8787 100%);
            color: #fff;
            border: none;
            border-radius: 8px;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.3s;
            margin-top: 8px;
        }

        .login-btn:hover {
            transform: translateY(-2px);
            box-shadow: 0 6px 20px rgba(255, 107, 107, 0.3);
        }

        /* 社交登录区域 */
        .social-login {
            margin-top: 32px;
            padding-top: 24px;
            border-top: 1px solid #e8e8e8;
        }

        .social-login-title {
            text-align: center;
            font-size: 13px;
            color: #999;
            margin-bottom: 16px;
        }

        .social-buttons {
            display: flex;
            justify-content: center;
            align-items: center;
            gap: 12px;
            flex-wrap: wrap;
        }

        .social-btn {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 8px 16px;
            border: 1px solid #e8e8e8;
            border-radius: 20px;
            background: #fff;
            cursor: pointer;
            font-size: 14px;
            color: #666;
            text-decoration: none;
            transition: all 0.3s;
        }

        .social-btn:hover {
            transform: translateY(-1px);
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
        }

        .social-btn.wechat {
            border-color: #07c160;
            color: #07c160;
        }

        .social-btn.qq {
            border-color: #12b7f5;
            color: #12b7f5;
        }

        .social-btn.github {
            border-color: #24292e;
            color: #24292e;
        }

        .social-btn.weibo {
            border-color: #ff8200;
            color: #ff8200;
        }

        .social-divider {
            color: #ddd;
        }

        .extra-links {
            display: flex;
            justify-content: center;
            gap: 16px;
            margin-top: 16px;
        }

        .extra-link {
            font-size: 14px;
            color: #666;
            text-decoration: none;
            transition: color 0.3s;
        }

        .extra-link:hover {
            color: #ff6b6b;
        }

        .footer-note {
            text-align: center;
            font-size: 12px;
            color: #bbb;
            margin-top: 24px;
        }
    </style>
</head>
<body>
    <div class="login-container">
        <div class="login-header">
            <h1>登录黑马头条</h1>
        </div>

        <!-- Tab切换 -->
        <div class="login-tabs">
            <div class="tab-item active" onclick="switchTab('password')">密码登录</div>
            <div class="tab-item" onclick="switchTab('sms')">短信登录</div>
        </div>

        <!-- 密码登录表单 -->
        <div id="passwordForm" class="form-section active">
            <div class="form-group">
                <input type="text" class="form-input" placeholder="账号名/手机号/邮箱" id="username" />
            </div>
            <div class="form-group">
                <div class="password-wrapper">
                    <input type="password" class="form-input" placeholder="密码" id="password" />
                    <span class="password-toggle" onclick="togglePassword()">👁️</span>
                </div>
            </div>
            <button class="login-btn" onclick="passwordLogin()">登录</button>
        </div>

        <!-- 短信登录表单 -->
        <div id="smsForm" class="form-section">
            <div class="form-group">
                <input type="tel" class="form-input" placeholder="请输入手机号" id="phone" maxlength="11" />
            </div>
            <div class="form-group" style="display: flex; gap: 12px;">
                <input type="text" class="form-input" placeholder="验证码" id="smsCode" maxlength="6" style="flex: 1;" />
                <button onclick="sendSmsCode()" id="sendCodeBtn" style="padding: 14px 20px; background: #f8f9fa; border: 1px solid #e8e8e8; border-radius: 8px; cursor: pointer; font-size: 14px; white-space: nowrap;">获取验证码</button>
            </div>
            <button class="login-btn" onclick="smsLogin()">登录</button>
        </div>

        <!-- 社交登录区域 -->
        <div class="social-login">
            <div class="social-login-title">其他登录方式</div>
            <div class="social-buttons">
                <a href="#" class="social-btn wechat">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 0 1 .213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 0 0 .167-.054l1.903-1.114a.864.864 0 0 1 .717-.098 10.16 10.16 0 0 0 2.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348zM5.785 5.991c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178A1.17 1.17 0 0 1 4.623 7.17c0-.651.52-1.18 1.162-1.18zm5.813 0c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178 1.17 1.17 0 0 1-1.162-1.178c0-.651.52-1.18 1.162-1.18zm5.34 2.867c-1.797-.052-3.746.512-5.28 1.786-1.72 1.428-2.687 3.72-1.78 6.22.942 2.453 3.666 4.229 6.884 4.229.826 0 1.622-.12 2.361-.336a.722.722 0 0 1 .598.082l1.584.926a.272.272 0 0 0 .14.045c.134 0 .24-.11.24-.245 0-.06-.024-.12-.04-.178l-.325-1.233a.49.49 0 0 1 .178-.554C23.028 18.48 24 16.82 24 14.98c0-3.21-2.931-5.952-7.062-6.122zm-2.18 2.769c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.97-.982zm4.844 0c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.97-.982z"/>
                    </svg>
                    微信登录
                </a>
                <span class="social-divider">|</span>
                <a href="#" class="social-btn qq">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm3.5 14.5h-7v-1.5h7v1.5zm0-3h-7V12h7v1.5zm0-3h-7V9h7v1.5zm0-3h-7V6h7v1.5z"/>
                    </svg>
                    QQ登录
                </a>
                <span class="social-divider">|</span>
                <a href="#" class="social-btn github">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M12 0C5.374 0 0 5.373 0 12c0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23A11.509 11.509 0 0 1 12 5.803c1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576C20.566 21.797 24 17.3 24 12c0-6.627-5.373-12-12-12z"/>
                    </svg>
                    GitHub登录
                </a>
                <span class="social-divider">|</span>
                <a href="#" class="social-btn weibo">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M10.098 20.323c-3.977.391-7.414-1.406-7.672-4.02-.259-2.609 2.759-5.047 6.74-5.441 3.979-.394 7.413 1.404 7.671 4.018.259 2.6-2.759 5.049-6.739 5.443zm-2.315-7.003c-.538.825-1.33 1.457-1.935 1.399-.622-.059-.697-1.07-.126-1.947.562-.866 1.409-1.54 2.006-1.414.615.059.678 1.066.055 1.962zm1.01 2.026c-.347.533-.864.937-1.273.875-.412-.065-.43-.73-.058-1.282.367-.544.867-.93 1.284-.848.411.078.394.727.047 1.255zm6.56-3.446c-1.094.254-2.325-.467-2.799-1.655-.466-1.167-.076-2.49.99-2.825 1.073-.342 2.276.362 2.777 1.542.499 1.174.117 2.523-.968 2.938zM20.5 5.371c0-.674-.554-1.222-1.238-1.222H3.738C3.055 4.149 2.5 4.697 2.5 5.371v12.262c0 .675.554 1.221 1.238 1.221h15.524c.684 0 1.238-.546 1.238-1.221V5.371z"/>
                    </svg>
                    微博登录
                </a>
            </div>
            <div class="extra-links">
                <a href="#" class="extra-link">忘记密码</a>
                <span style="color: #ddd;">|</span>
                <a href="#" class="extra-link">立即注册</a>
            </div>
        </div>

        <p class="footer-note">登录即表示您同意我们的服务条款和隐私政策</p>
    </div>

    <script>
        /**
         * Tab切换功能
         */
        function switchTab(tabName) {
            // 更新Tab样式
            var tabs = document.querySelectorAll('.tab-item');
            tabs.forEach(function(tab) {
                tab.classList.remove('active');
            });
            event.target.classList.add('active');

            // 显示对应表单
            var forms = document.querySelectorAll('.form-section');
            forms.forEach(function(form) {
                form.classList.remove('active');
            });

            if (tabName === 'password') {
                document.getElementById('passwordForm').classList.add('active');
            } else if (tabName === 'sms') {
                document.getElementById('smsForm').classList.add('active');
            }
        }

        /**
         * 密码可见性切换
         */
        function togglePassword() {
            var passwordInput = document.getElementById('password');
            var toggleIcon = document.querySelector('.password-toggle');
            
            if (passwordInput.type === 'password') {
                passwordInput.type = 'text';
                toggleIcon.textContent = '';
            } else {
                passwordInput.type = 'password';
                toggleIcon.textContent = '👁️';
            }
        }

        /**
         * 密码登录
         */
        function passwordLogin() {
            var username = document.getElementById('username').value.trim();
            var password = document.getElementById('password').value;

            if (!username) {
                alert('请输入账号名/手机号/邮箱');
                return;
            }
            if (!password) {
                alert('请输入密码');
                return;
            }

            // TODO: 调用后端登录接口
            console.log('密码登录:', { username: username, password: password });
            alert('登录功能待实现');
        }

        /**
         * 发送短信验证码
         */
        function sendSmsCode() {
            var phone = document.getElementById('phone').value.trim();
            var btn = document.getElementById('sendCodeBtn');

            if (!phone || !/^1[3-9]\d{9}$/.test(phone)) {
                alert('请输入正确的手机号');
                return;
            }

            // 禁用按钮并开始倒计时
            btn.disabled = true;
            btn.style.background = '#e8e8e8';
            btn.style.cursor = 'not-allowed';
            
            var countdown = 60;
            btn.textContent = countdown + 's后重新获取';
            
            var timer = setInterval(function() {
                countdown--;
                if (countdown <= 0) {
                    clearInterval(timer);
                    btn.disabled = false;
                    btn.style.background = '#f8f9fa';
                    btn.style.cursor = 'pointer';
                    btn.textContent = '获取验证码';
                } else {
                    btn.textContent = countdown + 's后重新获取';
                }
            }, 1000);

            // TODO: 调用后端发送验证码接口
            console.log('发送验证码到:', phone);
            alert('验证码已发送（功能待实现）');
        }

        /**
         * 短信登录
         */
        function smsLogin() {
            var phone = document.getElementById('phone').value.trim();
            var code = document.getElementById('smsCode').value.trim();

            if (!phone || !/^1[3-9]\d{9}$/.test(phone)) {
                alert('请输入正确的手机号');
                return;
            }
            if (!code || code.length !== 6) {
                alert('请输入6位验证码');
                return;
            }

            // TODO: 调用后端短信登录接口
            console.log('短信登录:', { phone: phone, code: code });
            alert('登录功能待实现');
        }
    </script>
</body>
</html>
