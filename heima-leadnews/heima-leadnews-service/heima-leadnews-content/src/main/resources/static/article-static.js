(function() {
    var articleId = window.ARTICLE_ID || '0';
    var authorId = window.AUTHOR_ID || 0;
    var tocLinks = document.querySelectorAll('.toc-list a');
    var headings = Array.from(document.querySelectorAll('.article-body h1, .article-body h2, .article-body h3'));

    // ========== 工具函数 ==========
    // 与 Vue SPA 共享登录态：Token 存于 localStorage 的 ACCESS_TOKEN，请求头使用 accToken
    function getToken() {
        return localStorage.getItem('ACCESS_TOKEN') || '';
    }

    function getUserInfoCache() {
        try {
            var str = localStorage.getItem('USER_INFO');
            return str ? JSON.parse(str) : null;
        } catch (e) { return null; }
    }

    function getHeaders() {
        var headers = { 'Content-Type': 'application/json; charset=UTF-8' };
        var token = getToken();
        if (token) {
            headers['accToken'] = token;
        }
        return headers;
    }

    function isLoggedIn() {
        return !!getToken();
    }

    function formatTime(ts) {
        if (!ts) return '';
        // 兼容秒级(10位)与毫秒级(13位)时间戳
        var num = Number(ts);
        if (isNaN(num)) return '';
        if (num < 1e12) num = num * 1000;
        var d = new Date(num);
        if (isNaN(d.getTime())) return '';
        var now = new Date();
        var diff = Math.floor((now - d) / 1000);
        if (diff < 0) diff = 0;
        if (diff < 60) return '刚刚';
        if (diff < 3600) return Math.floor(diff / 60) + '分钟前';
        if (diff < 86400) return Math.floor(diff / 3600) + '小时前';
        return Math.floor(diff / 86400) + '天前';
    }

    function escapeHtml(text) {
        if (!text) return '';
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(text));
        return div.innerHTML;
    }

    // 渲染评论内容：将 ![alt](url) 图片语法渲染为 <img>，其余内容转义
    function renderContent(content) {
        if (!content) return '';
        var parts = String(content).split(/!\[([^\]]*)\]\(([^)]+)\)/);
        var html = '';
        for (var i = 0; i < parts.length; i++) {
            if (i % 3 === 1) continue; // alt 文本
            if (i % 3 === 2) {
                var url = cleanImageUrl(parts[i].trim());
                html += '<img src="' + escapeHtml(url) + '" alt="' + escapeHtml(parts[i - 1]) + '" class="comment-image">';
                continue;
            }
            html += escapeHtml(parts[i]);
        }
        return html;
    }

    // 去掉图片 URL 中的 ? 及其往后签名参数，仅保留可长期访问的对象地址
    function cleanImageUrl(u) {
        if (!u) return '';
        var idx = u.indexOf('?');
        return idx > 0 ? u.substring(0, idx) : u;
    }

    // 渲染评论附带图片列表（独立字段，展示在评论信息下方）
    function renderPicsHtml(pics) {
        if (!pics || !pics.length) return '';
        var html = '<div class="comment-pics">';
        for (var k = 0; k < pics.length; k++) {
            html += '<img src="' + escapeHtml(cleanImageUrl(pics[k])) + '" alt="评论图片" class="comment-image">';
        }
        html += '</div>';
        return html;
    }

    function apiGet(url) {
        return fetch(url, { headers: getHeaders() }).then(function(r) { return r.json(); });
    }

    function apiPost(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: getHeaders(),
            body: body ? JSON.stringify(body) : undefined
        }).then(function(r) { return r.json(); });
    }

    // ========== 阅读量上报（浏览计数 + 接入等级体系） ==========
    // 每次打开文章详情页时上报一次浏览（同一篇文章同一天最多计一次，避免刷新刷浏览）
    function reportRead() {
        if (!articleId || articleId === '0') return;
        var today = new Date();
        var dayStr = today.getFullYear() + '-' + (today.getMonth() + 1) + '-' + today.getDate();
        var guardKey = 'article_viewed_' + articleId + '_' + dayStr;
        try {
            if (localStorage.getItem(guardKey)) return; // 今日已计过
        } catch (e) { /* ignore */ }
        apiPost('/content/api/v1/read_behavior', {
            articleId: articleId,  // 保留字符串，避免雪花ID经 Number() 转换丢精度导致阅读数不累加
            count: 1,
            readDuration: 0,
            percentage: 0,
            loadDuration: 0
        }).then(function(res) {
            try { localStorage.setItem(guardKey, '1'); } catch (e) { /* ignore */ }
            // 仅当服务端判定"本次计入阅读数"时才累加本地展示值，避免重复浏览/未登录造成展示漂移
            if (res && res.data && res.data.counted) {
                incReadCount();
            }
        }).catch(function() {
            // 未登录或网络异常时静默失败，不影响页面
        });
    }
    function incReadCount() {
        function inc(el) { if (el) { var n = parseInt(el.textContent, 10) || 0; el.textContent = n + 1; } }
        inc(document.getElementById('readCountHeader'));
        inc(document.getElementById('readCountSidebar'));
    }
    reportRead();

    // ========== 轻提示 ==========
    var toastTimer = null;
    function showToast(msg) {
        var el = document.getElementById('loginToast');
        if (!el) return;
        el.textContent = msg;
        el.classList.add('show');
        if (toastTimer) clearTimeout(toastTimer);
        toastTimer = setTimeout(function() { el.classList.remove('show'); }, 2000);
    }

    // ========== 顶栏用户状态同步（与主页 Web 端顶栏保持一致） ==========
    function syncTopBar() {
        var loginBtn = document.getElementById('topLoginBtn');
        var userInfo = document.getElementById('topUserInfo');
        var writeBtn = document.getElementById('topWriteBtn');
        var avatar = document.getElementById('topAvatar');
        var userName = document.getElementById('topUserName');
        if (isLoggedIn()) {
            loginBtn.style.display = 'none';
            userInfo.style.display = 'flex';
            writeBtn.style.display = 'inline-block';
            var info = getUserInfoCache() || {};
            avatar.src = info.avatar || '';
            userName.textContent = info.nickName || '用户';
        } else {
            loginBtn.style.display = 'inline-block';
            userInfo.style.display = 'none';
            writeBtn.style.display = 'none';
        }
    }

    function initTopBar() {
        syncTopBar();
        // 品牌 → 首页
        var brand = document.getElementById('topBrandLink');
        if (brand) brand.addEventListener('click', function() { window.location.href = '/home'; });
        // 主导航
        document.querySelectorAll('.nav-link[data-nav]').forEach(function(link) {
            link.addEventListener('click', function() {
                var nav = this.getAttribute('data-nav');
                if (nav === 'home') window.location.href = '/home';
                else if (nav === 'pins') window.location.href = '/pins';
                else if (nav === 'course') window.location.href = '/course';
            });
        });
        // 搜索
        var searchInput = document.getElementById('topSearchInput');
        var searchBtn = document.getElementById('topSearchBtn');
        function doSearch() {
            var kw = searchInput.value.trim();
            if (!kw) return;
            window.location.href = '/search_result?keyword=' + encodeURIComponent(kw);
        }
        if (searchBtn) searchBtn.addEventListener('click', doSearch);
        if (searchInput) searchInput.addEventListener('keydown', function(e) { if (e.key === 'Enter') doSearch(); });
        // 写文章
        var writeBtn = document.getElementById('topWriteBtn');
        if (writeBtn) {
            writeBtn.addEventListener('click', function() {
                if (!isLoggedIn()) { openLoginModal(); return; }
                window.location.href = '/creator/dashboard';
            });
        }
        // 用户信息 → 个人主页
        var userInfo = document.getElementById('topUserInfo');
        if (userInfo) {
            userInfo.addEventListener('click', function() {
                var info = getUserInfoCache();
                var userId = info && info.userId;
                if (userId) window.location.href = '/user/' + userId;
            });
        }
        // 登录按钮
        var loginBtn = document.getElementById('topLoginBtn');
        if (loginBtn) loginBtn.addEventListener('click', openLoginModal);
    }

    // ========== 登录弹窗交互（验证码/密码两种模式） ==========
    function openLoginModal() {
        var overlay = document.getElementById('loginOverlay');
        if (overlay) overlay.classList.add('open');
    }
    function closeLoginModal() {
        var overlay = document.getElementById('loginOverlay');
        if (overlay) overlay.classList.remove('open');
    }

    function toggleLoginMode() {
        var codeArea = document.getElementById('codeLoginArea');
        var pwdArea = document.getElementById('passwordLoginArea');
        var subtitle = document.getElementById('loginSubtitle');
        var toggleBtn = document.getElementById('loginToggleMode');
        var isPwd = codeArea.style.display === 'none';
        if (isPwd) {
            codeArea.style.display = '';
            pwdArea.style.display = 'none';
            subtitle.textContent = '验证码登录';
            toggleBtn.textContent = '密码登录';
        } else {
            codeArea.style.display = 'none';
            pwdArea.style.display = '';
            subtitle.textContent = '手机号或邮箱登录';
            toggleBtn.textContent = '验证码登录';
        }
    }

    function getLoginCode() {
        var phone = document.getElementById('loginPhone').value.trim();
        if (!(/^1[3-9]\d{9}$/.test(phone))) {
            showToast('请输入正确的手机号');
            return;
        }
        var btn = document.getElementById('loginGetCode');
        btn.disabled = true;
        btn.classList.add('disabled');
        var countdown = 60;
        btn.textContent = countdown + 's后重试';
        var timer = setInterval(function() {
            countdown--;
            if (countdown <= 0) {
                clearInterval(timer);
                btn.disabled = false;
                btn.classList.remove('disabled');
                btn.textContent = '获取验证码';
            } else {
                btn.textContent = countdown + 's后重试';
            }
        }, 1000);
        var url = '/user/api/v1/login/code?phone=' + encodeURIComponent(phone) + '&platform=app&tag=login';
        fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json; charset=UTF-8' } })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res && res.code === 200) {
                    if (res.data) {
                        document.getElementById('loginCode').value = String(res.data);
                        showToast('验证码已自动填充');
                    } else {
                        showToast('验证码已发送');
                    }
                } else {
                    showToast((res && (res.message || res.errorMessage)) || '获取验证码失败');
                }
            })
            .catch(function() { showToast('获取验证码失败'); });
    }

    function loginByCode() {
        var phone = document.getElementById('loginPhone').value.trim();
        var code = document.getElementById('loginCode').value.trim();
        if (!phone || phone.length < 11) { showToast('请输入正确的手机号'); return; }
        if (!code) { showToast('请输入验证码'); return; }
        doLogin({ phoneOrEmail: phone, code: code, platform: 'app' });
    }

    function loginByPassword() {
        var account = document.getElementById('loginAccount').value.trim();
        var pwd = document.getElementById('loginPassword').value.trim();
        if (!account) { showToast('请输入手机号或邮箱'); return; }
        if (!pwd) { showToast('请输入密码'); return; }
        doLogin({ phoneOrEmail: account, password: pwd, platform: 'app' });
    }

    function doLogin(body) {
        apiPost('/user/api/v1/login/login_auth', body).then(function(res) {
            if (res && res.code === 200 && res.data) {
                var data = res.data;
                if (data.accessToken) localStorage.setItem('ACCESS_TOKEN', data.accessToken);
                if (data.refreshToken) localStorage.setItem('REFRESH_TOKEN', data.refreshToken);
                if (data.userId || data.nickName) {
                    localStorage.setItem('USER_INFO', JSON.stringify({
                        userId: data.userId || '',
                        nickName: data.nickName || '',
                        avatar: data.avatar || '',
                        phone: data.phone || ''
                    }));
                }
                closeLoginModal();
                syncTopBar();
                showToast('登录成功');
                // 登录后刷新评论输入框与文章交互状态
                checkCommentLogin();
                loadArticleDetail();
            } else {
                showToast((res && (res.message || res.errorMessage)) || '登录失败');
            }
        }).catch(function() { showToast('登录失败，请重试'); });
    }

    function socialLogin(platform) {
        if (platform === 'wechat') {
            showToast('请使用微信扫码登录');
            return;
        }
        var redirect = encodeURIComponent('https://195b7e5b.r40.cpolar.top/oauth/callback');
        var urls = {
            weibo: 'https://api.weibo.com/oauth2/authorize?client_id=3770872274&redirect_uri=' + redirect + '&response_type=code&state=weibo',
            github: 'https://github.com/login/oauth/authorize?client_id=Ov23liBlhpjPoc6XKPbf&redirect_uri=' + redirect + '&scope=user&response_type=code&state=github'
        };
        if (urls[platform]) window.location.href = urls[platform];
    }

    function initLoginModal() {
        var overlay = document.getElementById('loginOverlay');
        var closeBtn = document.getElementById('loginCloseBtn');
        var toggleBtn = document.getElementById('loginToggleMode');
        var getCodeBtn = document.getElementById('loginGetCode');
        var codeSubmit = document.getElementById('loginSubmitBtn');
        var pwdSubmit = document.getElementById('pwdLoginSubmitBtn');
        if (closeBtn) closeBtn.addEventListener('click', closeLoginModal);
        if (overlay) {
            overlay.addEventListener('click', function(e) {
                if (e.target === overlay) closeLoginModal();
            });
        }
        if (toggleBtn) toggleBtn.addEventListener('click', toggleLoginMode);
        if (getCodeBtn) getCodeBtn.addEventListener('click', getLoginCode);
        if (codeSubmit) codeSubmit.addEventListener('click', loginByCode);
        if (pwdSubmit) pwdSubmit.addEventListener('click', loginByPassword);
        // 社交登录
        document.querySelectorAll('.login-social-item').forEach(function(item) {
            item.addEventListener('click', function() {
                socialLogin(this.getAttribute('data-social'));
            });
        });
        // 忘记密码
        var forgetLink = document.getElementById('loginForgetLink');
        if (forgetLink) {
            forgetLink.addEventListener('click', function() {
                showToast('请联系管理员重置密码');
            });
        }
    }

    // ========== 平滑滚动 ==========
    function bindTocClick(links) {
        links.forEach(function(link) {
            link.addEventListener('click', function(e) {
                var targetId = this.getAttribute('data-target');
                var target = document.getElementById(targetId);
                if (target) {
                    e.preventDefault();
                    var top = target.getBoundingClientRect().top + window.pageYOffset - 72;
                    window.scrollTo({ top: top, behavior: 'smooth' });
                }
                closeDrawer();
            });
        });
    }

    // 重新绑定 TOC 链接（在内容加载后调用）
    function rebindToc() {
        tocLinks = document.querySelectorAll('.toc-list a');
        headings = Array.from(document.querySelectorAll('.article-body h1, .article-body h2, .article-body h3'));
        bindTocClick(tocLinks);
        highlightToc();
    }

    bindTocClick(tocLinks);

    // 高亮当前目录
    function highlightToc() {
        var scrollPos = window.pageYOffset + 80;
        var current = null;
        headings.forEach(function(h) {
            if (h.offsetTop <= scrollPos) {
                current = h;
            }
        });
        tocLinks.forEach(function(link) {
            link.classList.remove('active');
        });
        if (current) {
            var activeLink = document.querySelector('.toc-list a[data-target="' + current.id + '"]');
            if (activeLink) activeLink.classList.add('active');
        }
    }
    window.addEventListener('scroll', highlightToc);
    highlightToc();

    // ========== 侧边栏随滚动切换内容阶段 ==========
    // 阅读过程中侧边栏依次展示：目录 -> 相关推荐 -> 精选内容，
    // 临近结尾重新展示 目录+相关推荐（目录定位到当前标题），
    // 读完结尾只展示 相关推荐+精选内容。
    function getReadingProgress() {
        var body = document.getElementById('articleContent');
        if (!body) return 1;
        var top = body.getBoundingClientRect().top + window.pageYOffset;
        var bottom = body.getBoundingClientRect().bottom + window.pageYOffset;
        if (bottom - top <= 0) return 1;
        var p = (window.pageYOffset - top) / (bottom - top);
        return Math.max(0, Math.min(1, p));
    }

    function updateSidebarStage() {
        var sidebar = document.getElementById('tocSidebar');
        if (!sidebar) return;
        var p = getReadingProgress();
        var phase;
        if (p < 0.3) {
            phase = 'toc';
        } else if (p < 0.6) {
            phase = 'related';
        } else if (p < 0.85) {
            phase = 'featured';
        } else if (p < 1.0) {
            phase = 'toc-related';
        } else {
            phase = 'end';
        }
        if (sidebar.getAttribute('data-stage') !== phase) {
            sidebar.setAttribute('data-stage', phase);
        }
    }
    window.addEventListener('scroll', updateSidebarStage, { passive: true });
    updateSidebarStage();

    // 移动端抽屉
    var tocFloatBtn = document.getElementById('tocFloatBtn');
    var tocDrawer = document.getElementById('tocDrawer');
    var drawerMask = document.getElementById('drawerMask');
    var closeDrawerBtn = document.getElementById('closeDrawer');

    function openDrawer() {
        tocDrawer.classList.add('open');
        drawerMask.classList.add('open');
    }
    function closeDrawer() {
        tocDrawer.classList.remove('open');
        drawerMask.classList.remove('open');
    }
    if (tocFloatBtn) tocFloatBtn.addEventListener('click', openDrawer);
    if (closeDrawerBtn) closeDrawerBtn.addEventListener('click', closeDrawer);
    if (drawerMask) drawerMask.addEventListener('click', closeDrawer);

    // 头像滚动显示
    var actionSidebar = document.getElementById('actionSidebar');
    var miniAuthorAvatar = document.getElementById('miniAuthorAvatar');
    function handleScrollForAvatar() {
        var scrollTop = window.pageYOffset;
        if (scrollTop > 300) {
            miniAuthorAvatar.classList.add('visible');
        } else {
            miniAuthorAvatar.classList.remove('visible');
        }
    }
    window.addEventListener('scroll', handleScrollForAvatar);
    handleScrollForAvatar();

    // ========== 图片灯箱 ==========
    function bindImageLightbox() {
        var articleImages = document.querySelectorAll('.article-body img');
        articleImages.forEach(function(img) {
            img.style.cursor = 'pointer';
            img.addEventListener('click', function() {
                lightboxImage.src = this.src;
                imageLightbox.classList.add('open');
                document.body.style.overflow = 'hidden';
            });
        });
    }

    var imageLightbox = document.getElementById('imageLightbox');
    var lightboxImage = document.getElementById('lightboxImage');
    var closeLightboxBtn = document.getElementById('closeLightbox');

    // 初始绑定（页面中已有的图片）
    bindImageLightbox();

    function closeLightbox() {
        imageLightbox.classList.remove('open');
        document.body.style.overflow = '';
    }
    if (closeLightboxBtn) closeLightboxBtn.addEventListener('click', closeLightbox);
    imageLightbox.addEventListener('click', function(e) {
        if (e.target === imageLightbox) closeLightbox();
    });
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') closeLightbox();
    });

    // ========== 文章详情加载 ==========
    var detailData = null;
    var diggCount = 0;
    var collectCount = 0;
    var commentCount = 0;

    function loadArticleDetail() {
        apiGet('/content/api/v1/article/detail/' + articleId).then(function(res) {
            if (res && res.code === 200 && res.data) {
                detailData = res.data;
                diggCount = res.data.diggCount || 0;
                collectCount = res.data.collectCount || 0;
                commentCount = res.data.commentCount || 0;
                updateSidebarCounts();
                updateActionButtons();
                updateFollowButton();
            }
        }).catch(function(err) {
            console.error('加载文章详情失败:', err);
        });
    }

    function updateSidebarCounts() {
        var sideLikeCount = document.querySelector('#sideLikeBtn .action-count');
        var sideCollectCount = document.querySelector('#sideCollectBtn .action-count');
        var sideCommentCount = document.querySelector('#sideCommentBtn .action-count');
        if (sideLikeCount) sideLikeCount.textContent = diggCount;
        if (sideCollectCount) sideCollectCount.textContent = collectCount;
        if (sideCommentCount) sideCommentCount.textContent = commentCount;
        updateCommentTitleCount(commentCount);
    }

    function updateActionButtons() {
        if (!detailData) return;
        var likeBtn = document.getElementById('likeBtn');
        var collectBtn = document.getElementById('collectBtn');
        var sideLikeBtn = document.getElementById('sideLikeBtn');
        var sideCollectBtn = document.getElementById('sideCollectBtn');

        if (detailData.isDigg) {
            likeBtn.classList.add('active');
            sideLikeBtn.classList.add('active');
            document.getElementById('likeBtnText').textContent = '已赞';
        }
        if (detailData.isCollect) {
            collectBtn.classList.add('active');
            sideCollectBtn.classList.add('active');
            document.getElementById('collectBtnText').textContent = '已收藏';
        }
    }

    function updateFollowButton() {
        if (!detailData) return;
        var followBtn = document.getElementById('followBtn');
        var authorFollowBtn = document.getElementById('authorFollowBtn');
        var endAuthorFollowBtn = document.getElementById('endAuthorFollowBtn');
        if (detailData.isFollow) {
            if (followBtn) { followBtn.classList.add('active'); followBtn.textContent = '已关注'; }
            if (authorFollowBtn) { authorFollowBtn.classList.add('active'); authorFollowBtn.textContent = '已关注'; }
            if (endAuthorFollowBtn) { endAuthorFollowBtn.classList.add('active'); endAuthorFollowBtn.textContent = '已关注'; }
        }
    }

    // ========== 点赞/收藏 动效 ==========
    // 点击按钮触发的弹跳/缩放动画
    function burstAction(el) {
        if (!el) return;
        el.classList.remove('bursting');
        // 强制回流以重启动画
        void el.offsetWidth;
        el.classList.add('bursting');
        setTimeout(function() { el.classList.remove('bursting'); }, 500);
    }
    // 计数平滑变化：数值变化时短暂放大跳动
    function bumpCount(el, newVal, oldVal) {
        if (!el) return;
        el.textContent = newVal;
        if (newVal === oldVal) return;
        el.classList.remove('count-bump');
        void el.offsetWidth;
        el.classList.add('count-bump');
        setTimeout(function() { el.classList.remove('count-bump'); }, 320);
    }

    // ========== 点赞/收藏/关注 API ==========
    var likeBtn = document.getElementById('likeBtn');
    if (likeBtn) {
        likeBtn.addEventListener('click', function() {
            if (!isLoggedIn()) { showToast('请先登录'); openLoginModal(); return; }
            var btn = this;
            burstAction(btn);
            apiPost('/content/api/v1/article/' + articleId + '/like').then(function(res) {
                if (res && res.code === 200 && res.data) {
                    btn.classList.toggle('active', res.data.liked);
                    document.getElementById('likeBtnText').textContent = res.data.liked ? '已赞' : '点赞';
                    var prev = diggCount;
                    diggCount = res.data.diggCount || 0;
                    updateSidebarCounts();
                    bumpCount(document.querySelector('#sideLikeBtn .action-count'), diggCount, prev);
                    var sideLikeBtn = document.getElementById('sideLikeBtn');
                    sideLikeBtn.classList.toggle('active', res.data.liked);
                    burstAction(sideLikeBtn);
                }
            }).catch(function(err) { console.error('点赞失败:', err); });
        });
    }

    var collectBtn = document.getElementById('collectBtn');
    if (collectBtn) {
        collectBtn.addEventListener('click', function() {
            if (!isLoggedIn()) { showToast('请先登录'); openLoginModal(); return; }
            var btn = this;
            burstAction(btn);
            apiPost('/content/api/v1/article/' + articleId + '/collect').then(function(res) {
                if (res && res.code === 200 && res.data) {
                    btn.classList.toggle('active', res.data.collected);
                    document.getElementById('collectBtnText').textContent = res.data.collected ? '已收藏' : '收藏';
                    var prev = collectCount;
                    collectCount = res.data.collectCount || 0;
                    updateSidebarCounts();
                    bumpCount(document.querySelector('#sideCollectBtn .action-count'), collectCount, prev);
                    var sideCollectBtn = document.getElementById('sideCollectBtn');
                    sideCollectBtn.classList.toggle('active', res.data.collected);
                    burstAction(sideCollectBtn);
                }
            }).catch(function(err) { console.error('收藏失败:', err); });
        });
    }

    function handleFollow() {
        if (!isLoggedIn()) { openLoginModal(); return; }
        apiPost('/content/api/v1/article/' + articleId + '/follow').then(function(res) {
            if (res && res.code === 200 && res.data) {
                var followed = res.data.followed;
                var btns = [document.getElementById('followBtn'), document.getElementById('authorFollowBtn'), document.getElementById('endAuthorFollowBtn')];
                btns.forEach(function(btn) {
                    if (btn) {
                        btn.classList.toggle('active', followed);
                        btn.textContent = followed ? '已关注' : '+ 关注';
                    }
                });
            }
        }).catch(function(err) { console.error('关注失败:', err); });
    }
    var followBtn = document.getElementById('followBtn');
    var authorFollowBtn = document.getElementById('authorFollowBtn');
    var endAuthorFollowBtn = document.getElementById('endAuthorFollowBtn');
    if (followBtn) followBtn.addEventListener('click', handleFollow);
    if (authorFollowBtn) authorFollowBtn.addEventListener('click', handleFollow);
    if (endAuthorFollowBtn) endAuthorFollowBtn.addEventListener('click', handleFollow);

    // 侧边栏点赞/收藏/分享/举报
    var sideLikeBtn = document.getElementById('sideLikeBtn');
    if (sideLikeBtn) {
        sideLikeBtn.addEventListener('click', function() {
            var lb = document.getElementById('likeBtn');
            if (lb) lb.click();
        });
    }
    var sideCollectBtn = document.getElementById('sideCollectBtn');
    if (sideCollectBtn) {
        sideCollectBtn.addEventListener('click', function() {
            var cb = document.getElementById('collectBtn');
            if (cb) cb.click();
        });
    }
    var sideCommentBtn = document.getElementById('sideCommentBtn');
    if (sideCommentBtn) {
        // 掘金式交互：点击左侧评论栏打开右侧评论抽屉，不打断阅读位置（不再滚动到文末评论区）
        sideCommentBtn.addEventListener('click', function() {
            openCommentDrawer();
        });
    }

    // ========== 专栏加载 ==========
    function loadColumn() {
        apiGet('/content/api/v1/article/' + articleId + '/column').then(function(res) {
            if (res && res.code === 200 && res.data && res.data.columnId) {
                var data = res.data;
                var columnCover = document.getElementById('columnCover');
                var columnName = document.getElementById('columnName');
                var columnDesc = document.getElementById('columnDesc');
                var columnArticleCnt = document.getElementById('columnArticleCnt');
                var columnFollowCnt = document.getElementById('columnFollowCnt');
                if (columnCover) columnCover.src = data.columnCover || '';
                if (columnName) columnName.textContent = data.columnTitle || '';
                if (columnDesc) columnDesc.textContent = data.columnDescription || '';
                if (columnArticleCnt) columnArticleCnt.textContent = data.articleCnt || 0;
                if (columnFollowCnt) columnFollowCnt.textContent = data.followCnt || 0;
                var subscribeBtn = document.getElementById('columnSubscribeBtn');
                if (data.isFollow) {
                    subscribeBtn.classList.add('active');
                    subscribeBtn.textContent = '已订阅';
                }
                // 上下篇导航
                var prevLink = document.getElementById('prevArticleLink');
                var nextLink = document.getElementById('nextArticleLink');
                if (data.prevArticleId) {
                    prevLink.href = '/content/article/' + data.prevArticleId;
                    prevLink.classList.remove('disabled');
                    prevLink.textContent = '← ' + (data.prevArticleTitle || '上一篇');
                }
                if (data.nextArticleId) {
                    nextLink.href = '/content/article/' + data.nextArticleId;
                    nextLink.classList.remove('disabled');
                    nextLink.textContent = (data.nextArticleTitle || '下一篇') + ' →';
                }
                var columnSection = document.getElementById('columnSection');
                if (columnSection) columnSection.classList.add('visible');
            }
        }).catch(function(err) { console.error('加载专栏信息失败:', err); });
    }

    // ========== 评论功能 ==========
    var commentCursor = '';
    var commentHasMore = false;
    var commentLoading = false;
    var replyToCommentId = null;
    var replyToRootId = null;
    var commentImages = [];   // 主评论框待提交图片
    var replyImages = [];     // 回复框待提交图片
    var replyLoadState = {};  // 二级回复分页加载状态 { commentId: { cursor, hasMore } }

    // 检查登录状态
    function checkCommentLogin() {
        var textarea = document.getElementById('commentTextarea');
        var submitBtn = document.getElementById('commentSubmitBtn');
        var loginTip = document.getElementById('loginTip');
        var avatar = document.getElementById('commentUserAvatar');
        var emojiBtn = document.getElementById('commentEmojiBtn');
        var imageBtn = document.getElementById('commentImageBtn');
        if (isLoggedIn()) {
            textarea.disabled = false;
            textarea.placeholder = '写下你的评论...';
            submitBtn.disabled = false;
            loginTip.style.display = 'none';
            avatar.src = '';
            if (emojiBtn) emojiBtn.disabled = false;
            if (imageBtn) imageBtn.disabled = false;
        } else {
            textarea.disabled = true;
            textarea.placeholder = '登录后参与评论';
            submitBtn.disabled = true;
            loginTip.style.display = 'block';
            avatar.src = '';
            if (emojiBtn) emojiBtn.disabled = true;
            if (imageBtn) imageBtn.disabled = true;
        }
    }
    checkCommentLogin();

    function renderComment(comment) {
        var li = document.createElement('li');
        li.className = 'comment-item';
        li.setAttribute('data-comment-id', comment.commentId);
        var userInfo = comment.userInfo || {};
        var userName = userInfo.userName || '匿名用户';
        var avatarUrl = userInfo.avatarLarge || '';

        var html = '<div class="comment-user">';
        html += '<div class="comment-user-avatar"><img src="' + avatarUrl + '" alt="avatar"></div>';
        html += '<span class="comment-user-name">' + escapeHtml(userName) + '</span>';
        html += '<span class="comment-user-time">' + formatTime(comment.ctime) + '</span>';
        html += '</div>';
        html += '<div class="comment-content">' + renderContent(comment.content) + '</div>';
        // 评论附带图片（独立字段 commentPics，展示在评论内容下方）
        html += renderPicsHtml(comment.commentPics);
        html += '<div class="comment-actions">';
        html += '<button class="comment-action-btn comment-like-btn' + (comment.isDigg ? ' active' : '') + '" data-comment-id="' + comment.commentId + '">';
        html += '<svg viewBox="0 0 24 24"><path d="M2 20h2v-9H2v9zm20-9c0-1.1-.9-2-2-2h-3.17c-.53-1.4-1.53-2.56-2.83-3.09V4c0-1.66-1.34-3-3-3S8 2.34 8 4v1.91C5.94 6.56 4.5 8.69 4.5 11v6.17l-1.83 1.83L4.17 20h12.5c1.66 0 3.08-1.03 3.65-2.5H22v-6.5z"/></svg>';
        html += '<span>' + (comment.diggCount || 0) + '</span>';
        html += '</button>';
        html += '<button class="comment-action-btn comment-reply-btn" data-comment-id="' + comment.commentId + '" data-root-id="' + comment.commentId + '">';
        html += '<svg viewBox="0 0 24 24"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>';
        html += '<span>回复</span>';
        html += '</button>';
        html += '</div>';

        // 子回复（含多级嵌套回复，服务端已按所属一级评论聚合在 replyInfos）
        var replies = comment.replyInfos || [];
        var needMoreBtn = comment.hasMoreReplies || (comment.replyCount > replies.length);
        if (replies.length > 0 || needMoreBtn) {
            html += '<div class="reply-list" data-comment-id="' + comment.commentId + '" data-root-id="' + comment.commentId + '">';
            replies.forEach(function(reply) {
                html += renderReplyItem(reply, comment.commentId);
            });
            if (needMoreBtn) {
                html += '<div class="reply-more"><button type="button" class="reply-more-btn" data-comment-id="' + comment.commentId + '" data-root-id="' + comment.commentId + '">查看全部' + (comment.replyCount || replies.length) + '条回复</button></div>';
            }
            html += '</div>';
        }

        li.innerHTML = html;
        return li;
    }

    // 渲染单条二级/更深回复（含回复附带图片，继续回复仍可嵌套）
    function renderReplyItem(reply, rootCommentId) {
        var replyUser = reply.userInfo || {};
        var html = '<div class="reply-item" data-comment-id="' + reply.commentId + '">';
        html += '<div class="reply-body">';
        html += '<span class="reply-user">' + escapeHtml(replyUser.userName || '匿名') + '：</span>';
        html += renderContent(reply.content);
        html += renderPicsHtml(reply.commentPics);
        html += '</div>';
        html += '<button class="reply-action-btn comment-reply-btn" data-comment-id="' + reply.commentId + '" data-root-id="' + rootCommentId + '">回复</button>';
        html += '</div>';
        return html;
    }

    function loadComments(append) {
        if (commentLoading) return;
        commentLoading = true;
        var url = '/content/api/v1/comment/article/' + articleId + '/comments?cursor=' + encodeURIComponent(commentCursor) + '&size=10';
        apiGet(url).then(function(res) {
            commentLoading = false;
            if (res && res.code === 200 && res.data) {
                var list = res.data.list || [];
                commentCursor = res.data.cursor || '';
                commentHasMore = res.data.has_more || false;
                var container = document.getElementById('commentList');
                var emptyEl = document.getElementById('commentEmpty');
                var loadMoreBtn = document.getElementById('commentLoadMore');

                if (!append) {
                    container.innerHTML = '';
                }

                if (list.length === 0 && !append) {
                    emptyEl.style.display = 'block';
                    loadMoreBtn.style.display = 'none';
                } else {
                    emptyEl.style.display = 'none';
                    list.forEach(function(comment) {
                        container.appendChild(renderComment(comment));
                    });
                    // 绑定评论按钮事件
                    bindCommentEvents();
                    loadMoreBtn.style.display = commentHasMore ? 'block' : 'none';
                }
            }
        }).catch(function(err) {
            commentLoading = false;
            console.error('加载评论失败:', err);
        });
    }

    function bindCommentEvents() {
        // 点赞评论
        document.querySelectorAll('.comment-like-btn').forEach(function(btn) {
            btn.removeEventListener('click', handleCommentLike);
            btn.addEventListener('click', handleCommentLike);
        });
        // 回复按钮
        document.querySelectorAll('.comment-reply-btn').forEach(function(btn) {
            btn.removeEventListener('click', handleCommentReply);
            btn.addEventListener('click', handleCommentReply);
        });
        // 查看更多回复
        document.querySelectorAll('.reply-more-btn').forEach(function(btn) {
            btn.removeEventListener('click', handleShowMoreReplies);
            btn.addEventListener('click', handleShowMoreReplies);
        });
        // 评论图片点击 → 灯箱放大
        document.querySelectorAll('.comment-image').forEach(function(img) {
            if (img._lbBound) return;
            img._lbBound = true;
            img.addEventListener('click', function() {
                lightboxImage.src = this.src;
                imageLightbox.classList.add('open');
                document.body.style.overflow = 'hidden';
            });
        });
    }

    function handleCommentLike(e) {
        e.stopPropagation();
        var btn = e.currentTarget;
        var commentId = btn.getAttribute('data-comment-id');
        if (!isLoggedIn()) {
            openLoginModal();
            return;
        }
        apiPost('/content/api/v1/comment/comment/' + commentId + '/like').then(function(res) {
            if (res && res.code === 200) {
                btn.classList.toggle('active');
                var countSpan = btn.querySelector('span');
                var current = parseInt(countSpan.textContent) || 0;
                countSpan.textContent = btn.classList.contains('active') ? (current + 1) : Math.max(0, current - 1);
            }
        }).catch(function(err) { console.error('点赞评论失败:', err); });
    }

    function handleCommentReply(e) {
        e.stopPropagation();
        var btn = e.currentTarget;
        var commentId = btn.getAttribute('data-comment-id');
        // 二级评论继续回复时，root 为一级评论ID；一级评论回复时 root 即自身
        var rootId = btn.getAttribute('data-root-id') || commentId;
        if (!isLoggedIn()) {
            openLoginModal();
            return;
        }
        // 移除已有的回复输入框
        var existing = document.querySelector('.reply-input-area');
        if (existing) existing.remove();
        replyToCommentId = commentId;
        replyToRootId = rootId;
        replyImages = [];
        var area = document.createElement('div');
        area.className = 'reply-input-area';
        area.innerHTML =
            '<textarea id="replyInput" placeholder="写下你的回复..." maxlength="1000"></textarea>' +
            '<div class="reply-image-preview" id="replyImagePreview"></div>' +
            '<div class="reply-input-footer">' +
            '<button type="button" class="comment-tool-btn reply-emoji-btn" title="表情">😊</button>' +
            '<button type="button" class="comment-tool-btn reply-image-btn" title="图片">图片</button>' +
            '<input type="file" class="reply-image-input" accept="image/*" style="display:none;">' +
            '<span class="comment-char-count"><span class="reply-char-count">0</span>/1000</span>' +
            '<button type="button" class="reply-send-btn" id="replySendBtn">发送</button>' +
            '<button type="button" class="reply-cancel-btn" id="replyCancelBtn">取消</button>' +
            '</div>';
        (btn.closest('.comment-item') || btn.parentNode.parentNode).appendChild(area);
        var replyInput = document.getElementById('replyInput');
        replyInput.focus();
        document.getElementById('replySendBtn').addEventListener('click', sendReply);
        document.getElementById('replyCancelBtn').addEventListener('click', function() {
            area.remove();
            replyToCommentId = null;
            replyToRootId = null;
            replyImages = [];
        });
        replyInput.addEventListener('keydown', function(ev) {
            if (ev.key === 'Enter' && !ev.shiftKey) {
                ev.preventDefault();
                sendReply();
            }
        });
        // 字数统计
        replyInput.addEventListener('input', function() {
            var countEl = area.querySelector('.reply-char-count');
            if (countEl) countEl.textContent = replyInput.value.length;
        });
        // 表情
        var emojiBtn = area.querySelector('.reply-emoji-btn');
        if (emojiBtn) {
            emojiBtn.addEventListener('click', function(ev) {
                ev.stopPropagation();
                openEmojiPicker(emojiBtn, replyInput);
            });
        }
        // 图片
        var imgBtn = area.querySelector('.reply-image-btn');
        var imgInput = area.querySelector('.reply-image-input');
        if (imgBtn) {
            imgBtn.addEventListener('click', function(ev) {
                ev.stopPropagation();
                imgInput.click();
            });
        }
        if (imgInput) {
            imgInput.addEventListener('change', function() {
                var file = imgInput.files[0];
                if (!file) return;
                imgInput.value = '';
                uploadCommentImage(file, function(url) {
                    replyImages.push(url);
                    renderImagePreview(area.querySelector('#replyImagePreview'), replyImages);
                });
            });
        }
    }

    function handleShowMoreReplies(e) {
        e.stopPropagation();
        var btn = e.currentTarget;
        var commentId = btn.getAttribute('data-comment-id');
        var rootId = btn.getAttribute('data-root-id') || commentId;
        var replyListEl = btn.closest('.reply-list');
        if (btn._loading) return;
        btn._loading = true;
        btn.disabled = true;
        btn.textContent = '加载中...';
        apiGet('/content/api/v1/comment/article/' + articleId + '/replies?rootId=' + encodeURIComponent(rootId) + '&cursor=' + (replyLoadState[commentId] ? replyLoadState[commentId].cursor : '') + '&size=10').then(function(res) {
            btn._loading = false;
            btn.disabled = false;
            if (res && res.code === 200 && res.data) {
                var list = res.data.list || [];
                var st = replyLoadState[commentId] || { cursor: 0, hasMore: false };
                st.cursor = res.data.cursor || 0;
                st.hasMore = !!res.data.has_more;
                replyLoadState[commentId] = st;

                // 追加新回复到"查看全部"按钮之前
                if (replyListEl) {
                    var moreWrap = btn.closest('.reply-more');
                    list.forEach(function(reply) {
                        var wrap = document.createElement('div');
                        wrap.className = 'reply-item-wrap';
                        wrap.innerHTML = renderReplyItem(reply, rootId);
                        if (moreWrap) {
                            replyListEl.insertBefore(wrap, moreWrap);
                        } else {
                            replyListEl.appendChild(wrap);
                        }
                    });
                    bindCommentEvents(); // 重新绑定回复按钮/图片点击
                }

                if (st.hasMore) {
                    btn.textContent = '加载更多回复';
                } else if (moreWrap) {
                    moreWrap.parentNode.removeChild(moreWrap);
                } else {
                    btn.style.display = 'none';
                }
            } else {
                btn.textContent = '查看全部回复';
                alert('加载回复失败: ' + (res && res.message ? res.message : '未知错误'));
            }
        }).catch(function(err) {
            btn._loading = false;
            btn.disabled = false;
            btn.textContent = '查看全部回复';
            console.error('加载更多回复失败:', err);
            alert('加载回复失败，请稍后重试');
        });
    }

    function sendReply() {
        var input = document.getElementById('replyInput');
        var content = input.value.trim();
        if (!content && replyImages.length === 0) return;
        if (!replyToCommentId) return;
        var body = { content: content, commentPics: replyImages.slice() };
        if (replyToRootId) {
            // 评论 ID 为自增主键（非雪花ID），Number 精度安全
            body.rootId = parseInt(replyToRootId);
        }
        var url = '/content/api/v1/comment/comment/' + replyToCommentId + '/reply';
        apiPost(url, body).then(function(res) {
            if (res && res.code === 200) {
                input.value = '';
                var area = document.querySelector('.reply-input-area');
                if (area) area.remove();
                replyToCommentId = null;
                replyToRootId = null;
                replyImages = [];
                // 重新加载评论
                commentCursor = '';
                loadComments(false);
            } else {
                alert('回复失败: ' + (res.message || '未知错误'));
            }
        }).catch(function(err) { console.error('回复失败:', err); });
    }

    // 发表评论
    var commentSubmitBtn = document.getElementById('commentSubmitBtn');
    if (commentSubmitBtn) {
        commentSubmitBtn.addEventListener('click', function() {
            if (!isLoggedIn()) {
                openLoginModal();
                return;
            }
            var textarea = document.getElementById('commentTextarea');
            var content = textarea.value.trim();
            if (!content && commentImages.length === 0) {
                alert('请输入评论内容');
                return;
            }
            var btn = this;
            btn.disabled = true;
            btn.textContent = '提交中...';
            apiPost('/content/api/v1/comment/article/' + articleId + '/comment', { content: content, commentPics: commentImages.slice() }).then(function(res) {
                btn.disabled = false;
                btn.textContent = '发表评论';
                if (res && res.code === 200) {
                    textarea.value = '';
                    commentImages = [];
                    renderImagePreview(document.getElementById('commentImagePreview'), commentImages);
                    updateCommentCharCount();
                    commentCursor = '';
                    loadComments(false);
                    commentCount++;
                    updateSidebarCounts();
                } else {
                    alert('评论失败: ' + (res.message || '未知错误'));
                }
            }).catch(function(err) {
                btn.disabled = false;
                btn.textContent = '发表评论';
                console.error('评论失败:', err);
                alert('评论失败，请稍后重试');
            });
        });
    }

    // ========== 右侧评论抽屉（掘金式：点击左侧评论栏打开，不打断阅读位置） ==========
    var commentDrawer = document.getElementById('commentDrawer');
    var commentDrawerMask = document.getElementById('commentDrawerMask');
    var drawerCommentCursor = '';
    var drawerCommentHasMore = false;
    var drawerCommentLoading = false;
    var drawerCommentImages = [];
    var drawerOpen = false;

    function openCommentDrawer() {
        if (!commentDrawer) return;
        drawerOpen = true;
        commentDrawer.classList.add('open');
        commentDrawerMask.classList.add('open');
        commentDrawer.setAttribute('aria-hidden', 'false');
        document.body.style.overflow = 'hidden';
        checkDrawerLogin();
        updateDrawerCommentCount(commentCount);
        // 每次打开重新加载，保证数据最新
        drawerCommentCursor = '';
        drawerCommentImages = [];
        renderImagePreview(document.getElementById('drawerCommentImagePreview'), drawerCommentImages);
        loadDrawerComments(false);
    }

    function closeCommentDrawer() {
        if (!commentDrawer || !drawerOpen) return;
        drawerOpen = false;
        commentDrawer.classList.remove('open');
        commentDrawerMask.classList.remove('open');
        commentDrawer.setAttribute('aria-hidden', 'true');
        document.body.style.overflow = '';
    }
    if (commentDrawerMask) {
        commentDrawerMask.addEventListener('click', closeCommentDrawer);
    }
    var drawerCloseBtn = document.getElementById('commentDrawerClose');
    if (drawerCloseBtn) drawerCloseBtn.addEventListener('click', closeCommentDrawer);
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape' && drawerOpen) closeCommentDrawer();
    });

    function loadDrawerComments(append) {
        if (drawerCommentLoading) return;
        drawerCommentLoading = true;
        var url = '/content/api/v1/comment/article/' + articleId + '/comments?cursor=' + encodeURIComponent(drawerCommentCursor) + '&size=10';
        apiGet(url).then(function(res) {
            drawerCommentLoading = false;
            if (res && res.code === 200 && res.data) {
                var list = res.data.list || [];
                drawerCommentCursor = res.data.cursor || '';
                drawerCommentHasMore = res.data.has_more || false;
                var container = document.getElementById('drawerCommentList');
                var emptyEl = document.getElementById('drawerCommentEmpty');
                var loadMoreBtn = document.getElementById('drawerCommentLoadMore');
                if (!container) return;
                if (!append) container.innerHTML = '';
                if (list.length === 0 && !append) {
                    emptyEl.style.display = 'block';
                    loadMoreBtn.style.display = 'none';
                } else {
                    emptyEl.style.display = 'none';
                    list.forEach(function(comment) {
                        container.appendChild(renderComment(comment));
                    });
                    bindCommentEvents();
                    loadMoreBtn.style.display = drawerCommentHasMore ? 'block' : 'none';
                }
            }
        }).catch(function(err) {
            drawerCommentLoading = false;
            console.error('加载评论失败:', err);
        });
    }
    var drawerCommentLoadMore = document.getElementById('drawerCommentLoadMore');
    if (drawerCommentLoadMore) {
        drawerCommentLoadMore.addEventListener('click', function() { loadDrawerComments(true); });
    }

    // 抽屉登录状态（复用主评区的 renderComment/渲染，仅控制禁用态与提示）
    function checkDrawerLogin() {
        var textarea = document.getElementById('drawerCommentTextarea');
        var submitBtn = document.getElementById('drawerCommentSubmitBtn');
        if (!textarea || !submitBtn) return;
        var loginTip = document.getElementById('drawerLoginTip');
        var avatar = document.getElementById('drawerCommentUserAvatar');
        var emojiBtn = document.getElementById('drawerCommentEmojiBtn');
        var imageBtn = document.getElementById('drawerCommentImageBtn');
        if (isLoggedIn()) {
            textarea.disabled = false;
            textarea.placeholder = '写下你的评论...';
            submitBtn.disabled = false;
            if (loginTip) loginTip.style.display = 'none';
            if (avatar) {
                var ui = getUserInfoCache();
                avatar.src = (ui && (ui.avatar || ui.avatarLarge || ui.headImage)) || '';
            }
            if (emojiBtn) emojiBtn.disabled = false;
            if (imageBtn) imageBtn.disabled = false;
        } else {
            textarea.disabled = true;
            textarea.placeholder = '登录后参与评论';
            submitBtn.disabled = true;
            if (loginTip) loginTip.style.display = 'block';
            if (avatar) avatar.src = '';
            if (emojiBtn) emojiBtn.disabled = true;
            if (imageBtn) imageBtn.disabled = true;
        }
    }

    function updateDrawerCommentCount(count) {
        var el = document.getElementById('drawerCommentTitleCount');
        if (el) el.textContent = count || 0;
    }

    // 抽屉字数统计
    var drawerCommentTextarea = document.getElementById('drawerCommentTextarea');
    if (drawerCommentTextarea) {
        drawerCommentTextarea.addEventListener('input', function() {
            var countEl = document.getElementById('drawerCommentCharCount');
            if (countEl) countEl.textContent = this.value.length;
        });
    }
    // 抽屉登录链接
    var drawerLoginLink = document.getElementById('drawerLoginLink');
    if (drawerLoginLink) {
        drawerLoginLink.addEventListener('click', function(e) { e.preventDefault(); openLoginModal(); });
    }
    // 抽屉表情
    var drawerCommentEmojiBtn = document.getElementById('drawerCommentEmojiBtn');
    if (drawerCommentEmojiBtn) {
        drawerCommentEmojiBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            openEmojiPicker(this, drawerCommentTextarea);
        });
    }
    // 抽屉图片（复用 OSS 直传）
    var drawerCommentImageBtn = document.getElementById('drawerCommentImageBtn');
    var drawerCommentImageInput = document.getElementById('drawerCommentImageInput');
    if (drawerCommentImageBtn && drawerCommentImageInput) {
        drawerCommentImageBtn.addEventListener('click', function() { drawerCommentImageInput.click(); });
        drawerCommentImageInput.addEventListener('change', function() {
            var file = drawerCommentImageInput.files[0];
            if (!file) return;
            drawerCommentImageInput.value = '';
            uploadCommentImage(file, function(url) {
                drawerCommentImages.push(url);
                renderImagePreview(document.getElementById('drawerCommentImagePreview'), drawerCommentImages);
            });
        });
    }
    // 抽屉发表评论
    var drawerCommentSubmitBtn = document.getElementById('drawerCommentSubmitBtn');
    if (drawerCommentSubmitBtn) {
        drawerCommentSubmitBtn.addEventListener('click', function() {
            if (!isLoggedIn()) { openLoginModal(); return; }
            var content = drawerCommentTextarea.value.trim();
            if (!content && drawerCommentImages.length === 0) { alert('请输入评论内容'); return; }
            var btn = this;
            btn.disabled = true;
            btn.textContent = '提交中...';
            apiPost('/content/api/v1/comment/article/' + articleId + '/comment', { content: content, commentPics: drawerCommentImages.slice() }).then(function(res) {
                btn.disabled = false;
                btn.textContent = '发表评论';
                if (res && res.code === 200) {
                    drawerCommentTextarea.value = '';
                    drawerCommentImages = [];
                    renderImagePreview(document.getElementById('drawerCommentImagePreview'), drawerCommentImages);
                    var countEl = document.getElementById('drawerCommentCharCount');
                    if (countEl) countEl.textContent = 0;
                    // 同步各处评论计数 + 刷新抽屉评论列表
                    commentCount++;
                    updateSidebarCounts();
                    updateDrawerCommentCount(commentCount);
                    drawerCommentCursor = '';
                    loadDrawerComments(false);
                } else {
                    alert('评论失败: ' + (res.message || '未知错误'));
                }
            }).catch(function(err) {
                btn.disabled = false;
                btn.textContent = '发表评论';
                console.error('评论失败:', err);
                alert('评论失败，请稍后重试');
            });
        });
    }

    // ========== 评论图片（OSS web 直传）/ 表情 ==========
    var emojiList = [
        '😀', '😃', '😄', '😁', '😅', '😂', '🤣', '😊', '😇', '🙂',
        '😉', '😌', '😍', '🥰', '😘', '😗', '😋', '😛', '😜', '🤪',
        '😝', '🤑', '🤗', '🤭', '🤫', '🤔', '🤐', '🤨', '😐', '😑',
        '😶', '😏', '😒', '🙄', '😬', '😪', '😮', '🤯', '😴', '🤤',
        '😭', '😤', '😡', '🤬', '😈', '💀', '💩', '🤡', '👻', '👽',
        '🤖', '😺', '😸', '😹', '😻', '😼', '😽', '🙀', '😿', '😾',
        '🙈', '🙉', '🙊', '💋', '💌', '💘', '💝', '💖', '💗', '💓',
        '💞', '💕', '❤️', '🧡', '💛', '💚', '💙', '💜', '🖤', '🤍',
        '💯', '🔥', '⭐', '👍', '👎', '👏', '🙌', '🤝', '💪', '✍️',
        '🎉', '🎊', '🎈', '✨', '🌟', '💥', '☀️', '🌙', '⚡', '💧'
    ];
    var commentEmojiPicker = null;
    var activeEmojiTextarea = null;

    // OSS web 直传：复用 /content/api/v1/media/oss/post_signature 签名方案
    function uploadCommentImage(file, onDone) {
        if (!file) return;
        apiGet('/content/api/v1/media/oss/post_signature').then(function(res) {
            if (!res || res.code !== 200 || !res.data) {
                throw new Error('获取上传签名失败');
            }
            var data = res.data;
            var ext = file.name.substring(file.name.lastIndexOf('.'));
            var objectKey = data.dir + Date.now() + '_' + Math.random().toString(36).substring(2, 8) + ext;
            var formData = new FormData();
            formData.append('name', file.name);
            formData.append('key', objectKey);
            formData.append('policy', data.policy);
            formData.append('OSSAccessKeyId', data.ossAccessKeyId);
            formData.append('success_action_status', '200');
            formData.append('signature', data.signature);
            formData.append('file', file);
            return fetch(data.host, { method: 'POST', body: formData, mode: 'no-cors' }).then(function() {
                return { host: data.host, objectKey: objectKey };
            });
        }).then(function(info) {
            // 获取可访问 URL（优先签名URL，失败则回落 host+key）
            return apiGet('/content/api/v1/media/oss/presigned_url?key=' + encodeURIComponent(info.objectKey)).then(function(pres) {
                if (pres && pres.code === 200 && pres.data && pres.data.url) {
                    onDone(pres.data.url);
                } else {
                    onDone(info.host + '/' + info.objectKey);
                }
            });
        }).catch(function(err) {
            console.error('评论图片上传失败:', err);
            alert('图片上传失败，请重试');
        });
    }

    // 渲染图片预览缩略图（带删除）
    function renderImagePreview(previewEl, images) {
        if (!previewEl) return;
        previewEl.innerHTML = '';
        (images || []).forEach(function(url, idx) {
            var item = document.createElement('div');
            item.className = 'preview-item';
            var img = document.createElement('img');
            img.src = url;
            img.alt = '预览';
            var rm = document.createElement('span');
            rm.className = 'preview-remove';
            rm.textContent = '×';
            rm.addEventListener('click', function() {
                images.splice(idx, 1);
                renderImagePreview(previewEl, images);
            });
            item.appendChild(img);
            item.appendChild(rm);
            previewEl.appendChild(item);
        });
    }

    // 初始化全局表情选择器
    function ensureEmojiPicker() {
        if (commentEmojiPicker) return;
        commentEmojiPicker = document.createElement('div');
        commentEmojiPicker.className = 'comment-emoji-picker';
        var grid = document.createElement('div');
        grid.className = 'comment-emoji-grid';
        emojiList.forEach(function(emo) {
            var s = document.createElement('span');
            s.className = 'comment-emoji-item';
            s.textContent = emo;
            s.addEventListener('click', function() {
                insertEmoji(emo);
            });
            grid.appendChild(s);
        });
        commentEmojiPicker.appendChild(grid);
        document.body.appendChild(commentEmojiPicker);
        document.addEventListener('click', function(ev) {
            if (commentEmojiPicker && !commentEmojiPicker.contains(ev.target)) {
                commentEmojiPicker.style.display = 'none';
            }
        });
    }

    // 打开表情选择器（锚定在按钮下方）
    function openEmojiPicker(anchorEl, textareaEl) {
        ensureEmojiPicker();
        activeEmojiTextarea = textareaEl;
        var rect = anchorEl.getBoundingClientRect();
        var left = rect.left;
        if (left + 320 > window.innerWidth) left = window.innerWidth - 320;
        commentEmojiPicker.style.top = (rect.bottom + 4) + 'px';
        commentEmojiPicker.style.left = left + 'px';
        commentEmojiPicker.style.display = 'block';
    }

    // 插入表情到活动输入框
    function insertEmoji(emo) {
        var ta = activeEmojiTextarea;
        if (!ta) return;
        var start = ta.selectionStart;
        var end = ta.selectionEnd;
        ta.value = ta.value.substring(0, start) + emo + ta.value.substring(end);
        ta.selectionStart = ta.selectionEnd = start + emo.length;
        ta.focus();
        if (commentEmojiPicker) commentEmojiPicker.style.display = 'none';
    }

    // 主评论框表情/图片/字数
    function updateCommentCharCount() {
        var textarea = document.getElementById('commentTextarea');
        var countEl = document.getElementById('commentCharCount');
        if (textarea && countEl) countEl.textContent = textarea.value.length;
    }
    var commentTextarea = document.getElementById('commentTextarea');
    if (commentTextarea) {
        commentTextarea.addEventListener('input', updateCommentCharCount);
    }
    var commentEmojiBtn = document.getElementById('commentEmojiBtn');
    if (commentEmojiBtn) {
        commentEmojiBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            openEmojiPicker(this, document.getElementById('commentTextarea'));
        });
    }
    var commentImageBtn = document.getElementById('commentImageBtn');
    var commentImageInput = document.getElementById('commentImageInput');
    if (commentImageBtn && commentImageInput) {
        commentImageBtn.addEventListener('click', function() {
            commentImageInput.click();
        });
        commentImageInput.addEventListener('change', function() {
            var file = commentImageInput.files[0];
            if (!file) return;
            commentImageInput.value = '';
            uploadCommentImage(file, function(url) {
                commentImages.push(url);
                renderImagePreview(document.getElementById('commentImagePreview'), commentImages);
            });
        });
    }

    // 评论区"登录"链接 → 打开登录弹窗
    var loginLink = document.getElementById('loginLink');
    if (loginLink) {
        loginLink.addEventListener('click', function(e) {
            e.preventDefault();
            openLoginModal();
        });
    }

    // 加载更多评论
    var commentLoadMore = document.getElementById('commentLoadMore');
    if (commentLoadMore) {
        commentLoadMore.addEventListener('click', function() {
            loadComments(true);
        });
    }

    // ========== 为你推荐（横排卡片） ==========
    var recommendCursor = '';
    var recommendHasMore = false;
    var recommendLoaded = false;

    function loadRecommend(append) {
        var url = '/content/api/v1/article/' + articleId + '/recommend?cursor=' + encodeURIComponent(recommendCursor) + '&size=6';
        apiGet(url).then(function(res) {
            if (res && res.code === 200 && res.data) {
                var list = res.data.list || [];
                recommendCursor = res.data.cursor || '';
                recommendHasMore = res.data.has_more || false;
                var container = document.getElementById('recommendList');
                var loadMoreBtn = document.getElementById('recommendLoadMore');
                if (!append) {
                    container.innerHTML = '';
                }
                list.forEach(function(item) {
                    var coverUrl = item.coverImage || '';
                    var a = document.createElement('a');
                    a.className = 'recommend-card';
                    a.href = '/content/article/' + item.articleId;
                    a.target = '_blank';
                    a.innerHTML = '<div class="recommend-card-cover">' +
                        (coverUrl ? '<img src="' + coverUrl + '" alt="" loading="lazy">' : '') +
                        '</div>' +
                        '<div class="recommend-card-info">' +
                        '<div class="recommend-card-title">' + escapeHtml(item.title) + '</div>' +
                        '<div class="recommend-card-meta">' +
                        '<span>' + escapeHtml(item.authorName || '') + '</span>' +
                        '<span class="meta-sep">·</span>' +
                        '<span>' + (item.viewCount || 0) + '阅读</span>' +
                        (item.categoryName ? '<span class="category-tag">' + escapeHtml(item.categoryName) + '</span>' : '') +
                        '</div></div>';
                    container.appendChild(a);
                });
                recommendLoaded = true;
                loadMoreBtn.style.display = recommendHasMore ? 'block' : 'none';
            }
        }).catch(function(err) { console.error('加载为你推荐失败:', err); });
    }

    var recommendLoadMore = document.getElementById('recommendLoadMore');
    if (recommendLoadMore) {
        recommendLoadMore.addEventListener('click', function() {
            loadRecommend(true);
        });
    }

    // 正文 85% 位置预加载「为你推荐」（页面较短时直接加载）
    function maybePreloadRecommend() {
        if (recommendLoaded) return;
        var articleBody = document.getElementById('articleContent');
        if (!articleBody) { loadRecommend(false); return; }
        var rect = articleBody.getBoundingClientRect();
        var scrollable = document.documentElement.scrollHeight - window.innerHeight;
        // 正文已滚过 85%，或剩余滚动量不足一屏时提前加载
        if (rect.bottom <= window.innerHeight * 0.85 || scrollable <= window.innerHeight * 0.5) {
            loadRecommend(false);
        }
    }
    window.addEventListener('scroll', maybePreloadRecommend, { passive: true });
    maybePreloadRecommend();

    // ========== 回到顶部 ==========
    var sideBackTopBtn = document.getElementById('sideBackTopBtn');
    if (sideBackTopBtn) {
        sideBackTopBtn.addEventListener('click', function() {
            window.scrollTo({ top: 0, behavior: 'smooth' });
        });
    }

    // ========== 读完提示 ==========
    // 正文进入视口后淡入「— 已读完，感谢阅读 —」
    var readEndHint = document.getElementById('readEndHint');
    if (readEndHint && 'IntersectionObserver' in window) {
        var hintObserver = new IntersectionObserver(function(entries) {
            entries.forEach(function(entry) {
                if (entry.isIntersecting) {
                    readEndHint.classList.add('show');
                    hintObserver.disconnect();
                }
            });
        }, { rootMargin: '-60px 0px 0px 0px' });
        hintObserver.observe(readEndHint);
    } else if (readEndHint) {
        readEndHint.classList.add('show');
    }

    // ========== 正文尾部作者卡片 ==========
    // 通过作者信息聚合接口拉取头像/昵称/简介/粉丝数/关注态并填充
    function loadEndAuthorCard() {
        var nameEl = document.getElementById('endAuthorName');
        var descEl = document.getElementById('endAuthorDesc');
        var followersEl = document.getElementById('endAuthorFollowers');
        var avatarEl = document.getElementById('endAuthorAvatar');
        var followBtnEl = document.getElementById('endAuthorFollowBtn');
        if (!nameEl || !authorId) return;
        apiGet('/content/api/v1/author/info?userId=' + authorId).then(function(res) {
            if (!res || res.code !== 200 || !res.data) return;
            var data = res.data;
            if (data.nickname) nameEl.textContent = data.nickname;
            if (data.avatar && avatarEl) avatarEl.src = data.avatar;
            // 简介：优先 bio，其次 职位 · 公司
            var desc = data.bio || '';
            if (!desc) {
                var parts = [];
                if (data.position) parts.push(data.position);
                if (data.company) parts.push(data.company);
                desc = parts.join(' · ');
            }
            descEl.textContent = desc || '这个人很懒，什么都没有留下~';
            if (followersEl) followersEl.textContent = data.followerCount || 0;
            if (followBtnEl && data.isFollowed) {
                followBtnEl.classList.add('active');
                followBtnEl.textContent = '已关注';
            }
        }).catch(function(err) { console.error('加载作者卡片失败:', err); });
    }

    // ========== 相关推荐（右侧边栏） ==========
    function loadRelated() {
        var url = '/content/api/v1/article/' + articleId + '/related?cursor=&size=5';
        apiGet(url).then(function(res) {
            var container = document.getElementById('relatedList');
            if (res && res.code === 200 && res.data) {
                var list = res.data.list || [];
                if (list.length === 0) {
                    container.innerHTML = '<li class="sidebar-recommend-empty">暂无相关推荐</li>';
                    return;
                }
                container.innerHTML = '';
                list.forEach(function(item) {
                    var li = document.createElement('li');
                    li.className = 'sidebar-recommend-item';
                    li.innerHTML = '<a href="/content/article/' + item.articleId + '" class="sidebar-recommend-link" target="_blank">' +
                        '<span class="sidebar-recommend-link-title">' + escapeHtml(item.title) + '</span>' +
                        '<span class="sidebar-recommend-link-meta">' + escapeHtml(item.authorName || '') + ' · ' + formatTime(item.publishTime) + '</span>' +
                        '</a>';
                    container.appendChild(li);
                });
            } else {
                container.innerHTML = '<li class="sidebar-recommend-empty">暂无相关推荐</li>';
            }
        }).catch(function(err) {
            console.error('加载相关推荐失败:', err);
            document.getElementById('relatedList').innerHTML = '<li class="sidebar-recommend-empty">加载失败</li>';
        });
    }

    // ========== 精选内容（右侧边栏） ==========
    function loadFeatured() {
        var url = '/content/api/v1/article/' + articleId + '/featured?cursor=&size=5';
        apiGet(url).then(function(res) {
            var container = document.getElementById('featuredList');
            if (res && res.code === 200 && res.data) {
                var list = res.data.list || [];
                if (list.length === 0) {
                    container.innerHTML = '<li class="sidebar-recommend-empty">暂无精选内容</li>';
                    return;
                }
                container.innerHTML = '';
                list.forEach(function(item) {
                    var li = document.createElement('li');
                    li.className = 'sidebar-recommend-item';
                    li.innerHTML = '<a href="/content/article/' + item.articleId + '" class="sidebar-recommend-link" target="_blank">' +
                        '<span class="sidebar-recommend-link-title">' + escapeHtml(item.title) + '</span>' +
                        '<span class="sidebar-recommend-link-meta">' + escapeHtml(item.authorName || '') + ' · ' + formatTime(item.publishTime) + '</span>' +
                        '</a>';
                    container.appendChild(li);
                });
            } else {
                container.innerHTML = '<li class="sidebar-recommend-empty">暂无精选内容</li>';
            }
        }).catch(function(err) {
            console.error('加载精选内容失败:', err);
            document.getElementById('featuredList').innerHTML = '<li class="sidebar-recommend-empty">加载失败</li>';
        });
    }

    // ========== TOC 折叠/展开 ==========
    var tocCollapseBtn = document.getElementById('tocCollapseBtn');
    var tocList = document.getElementById('tocList');
    if (tocCollapseBtn && tocList) {
        tocCollapseBtn.addEventListener('click', function() {
            var collapsed = tocList.classList.toggle('collapsed');
            tocCollapseBtn.textContent = collapsed ? '展开' : '收起';
            tocCollapseBtn.title = collapsed ? '展开' : '收起';
        });
    }

    // ========== 分享面板 ==========
    var sharePanel = document.getElementById('sharePanel');
    var shareLinkInput = document.getElementById('shareLinkInput');
    var closeSharePanelBtn = document.getElementById('closeSharePanel');
    var shareCopyBtn = document.getElementById('shareCopyBtn');
    var currentShareUrl = window.location.href;

    // 复制到剪贴板（优先 navigator.clipboard，降级 execCommand）
    function copyFallback(text) {
        var ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        var ok = false;
        try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
        document.body.removeChild(ta);
        return ok;
    }
    function copyToClipboard(text, successMsg) {
        var done = function(ok) {
            showToast(ok ? successMsg : '复制失败，请手动复制');
        };
        if (navigator.clipboard && window.isSecureContext) {
            navigator.clipboard.writeText(text).then(function() { done(true); }, function() { done(copyFallback(text)); });
        } else {
            done(copyFallback(text));
        }
    }

    function openSharePanel() {
        if (sharePanel) {
            if (shareLinkInput) shareLinkInput.value = currentShareUrl;
            sharePanel.classList.add('open');
        }
    }
    function closeSharePanel() {
        if (sharePanel) sharePanel.classList.remove('open');
    }
    if (closeSharePanelBtn) closeSharePanelBtn.addEventListener('click', closeSharePanel);
    // 点击面板外部关闭
    if (sharePanel) {
        document.addEventListener('click', function(e) {
            if (sharePanel.classList.contains('open') &&
                !sharePanel.contains(e.target) &&
                !document.getElementById('sideShareBtn').contains(e.target)) {
                closeSharePanel();
            }
        });
    }
    // 复制链接
    if (shareCopyBtn) {
        shareCopyBtn.addEventListener('click', function() {
            if (!shareLinkInput) return;
            copyToClipboard(shareLinkInput.value, '链接已复制');
            shareCopyBtn.textContent = '已复制';
            setTimeout(function() { shareCopyBtn.textContent = '复制链接'; }, 2000);
        });
    }
    // 分享选项
    document.querySelectorAll('.share-option').forEach(function(opt) {
        opt.addEventListener('click', function() {
            var share = this.getAttribute('data-share');
            var title = document.title || '分享文章';
            var desc = '推荐一篇文章，快来阅读吧！';
            var url = currentShareUrl;
            var wechatText = title + '\n' + url + '\n' + desc;
            switch (share) {
                case 'wechat':
                    // 微信：复制文案
                    copyToClipboard(wechatText, '微信分享文案已复制');
                    closeSharePanel();
                    break;
                case 'weibo':
                    window.open('https://service.weibo.com/share/share.php?title=' + encodeURIComponent(title) + '&url=' + encodeURIComponent(url) + '&appkey=&source=&pic=&ralateUid=&language=zh_cn', '_blank', 'width=615,height=505');
                    closeSharePanel();
                    break;
                case 'qq':
                    window.open('https://connect.qq.com/widget/shareqq/index.html?title=' + encodeURIComponent(title) + '&url=' + encodeURIComponent(url) + '&desc=' + encodeURIComponent(desc), '_blank', 'width=700,height=520');
                    closeSharePanel();
                    break;
                case 'juejin':
                    // 掘金：复制掘金风格文案
                    var juejinText = '推荐一篇文章：' + title + '\n' + url + '\n\n文章来源：逐日Coding';
                    copyToClipboard(juejinText, '已复制掘金分享文案');
                    closeSharePanel();
                    break;
            }
        });
    });
    // 侧边栏分享按钮
    var sideShareBtn = document.getElementById('sideShareBtn');
    if (sideShareBtn) {
        sideShareBtn.addEventListener('click', function() {
            if (sharePanel && sharePanel.classList.contains('open')) {
                closeSharePanel();
            } else {
                openSharePanel();
            }
        });
    }

    // ========== 举报弹窗 ==========
    var selectedReportReason = '';
    var reportImages = [];
    function openReportModal() {
        var overlay = document.getElementById('reportModalOverlay');
        if (overlay) overlay.classList.add('open');
        document.body.style.overflow = 'hidden';
        selectedReportReason = '';
        document.querySelectorAll('.report-option-btn').forEach(function(b) { b.classList.remove('selected'); });
        if (reportTextarea) reportTextarea.value = '';
        if (reportCharCount) reportCharCount.textContent = '0';
        // 重置已选图片
        reportImages = [];
        renderReportPreview();
    }
    function closeReportModal() {
        var overlay = document.getElementById('reportModalOverlay');
        if (overlay) overlay.classList.remove('open');
        document.body.style.overflow = '';
    }
    var closeReportBtn = document.getElementById('closeReportModal');
    var reportModalOverlay = document.getElementById('reportModalOverlay');
    var cancelReportBtn = document.getElementById('cancelReportBtn');
    var confirmReportBtn = document.getElementById('confirmReportBtn');
    if (closeReportBtn) closeReportBtn.addEventListener('click', closeReportModal);
    if (cancelReportBtn) cancelReportBtn.addEventListener('click', closeReportModal);
    if (reportModalOverlay) {
        reportModalOverlay.addEventListener('click', function(e) {
            if (e.target === reportModalOverlay) closeReportModal();
        });
    }
    // 举报真实提交
    if (confirmReportBtn) {
        confirmReportBtn.addEventListener('click', function() {
            if (!selectedReportReason) {
                showToast('请选择举报原因');
                return;
            }
            // 先上传所有图片，获取 URL 列表
            var uploadPromises = reportImages.map(function(file) {
                return new Promise(function(resolve) {
                    uploadReportImage(file, function(url) {
                        resolve(url);
                    });
                });
            });
            Promise.all(uploadPromises).then(function(urls) {
                var body = {
                    reason: selectedReportReason,
                    description: document.getElementById('reportTextarea').value || '',
                    imageUrls: urls.filter(function(u) { return u; })
                };
                return apiPost('/content/api/v1/article/' + articleId + '/report', body);
            }).then(function(res) {
                if (res && res.code === 200) {
                    showToast('举报已提交，感谢您的反馈！');
                    reportImages = [];
                    renderReportPreview();
                    closeReportModal();
                } else {
                    showToast(res ? (res.errorMessage || '提交失败') : '网络异常');
                }
            }).catch(function(err) {
                console.error('举报提交失败:', err);
                showToast('提交失败，请稍后重试');
            });
        });
    }
    // 举报图片上传（复用 OSS 直传）
    function uploadReportImage(file, onDone) {
        if (!file) return;
        apiGet('/content/api/v1/media/oss/post_signature').then(function(res) {
            if (!res || res.code !== 200 || !res.data) {
                showToast('获取上传签名失败');
                onDone('');
                return;
            }
            var data = res.data;
            var ext = file.name.substring(file.name.lastIndexOf('.'));
            var objectKey = data.dir + 'report/' + Date.now() + '_' + Math.random().toString(36).substring(2, 8) + ext;
            var formData = new FormData();
            formData.append('name', file.name);
            formData.append('key', objectKey);
            formData.append('policy', data.policy);
            formData.append('OSSAccessKeyId', data.ossAccessKeyId);
            formData.append('success_action_status', '200');
            formData.append('signature', data.signature);
            formData.append('file', file);
            return fetch(data.host, { method: 'POST', body: formData, mode: 'no-cors' }).then(function() {
                return { host: data.host, objectKey: objectKey };
            });
        }).then(function(info) {
            if (!info) { onDone(''); return; }
            return apiGet('/content/api/v1/media/oss/presigned_url?key=' + encodeURIComponent(info.objectKey)).then(function(pres) {
                if (pres && pres.code === 200 && pres.data && pres.data.url) {
                    onDone(pres.data.url);
                } else {
                    onDone(info.host + '/' + info.objectKey);
                }
            });
        }).catch(function(err) {
            console.error('举报图片上传失败:', err);
            onDone('');
        });
    }
    // 举报图片预览渲染
    function renderReportPreview() {
        var area = document.getElementById('reportUploadArea');
        if (!area) return;
        // 保留 upload-box
        var uploadBox = document.getElementById('reportUploadBox');
        // 清除旧的预览
        area.querySelectorAll('.report-preview-item').forEach(function(el) { el.remove(); });
        // 插入预览
        reportImages.forEach(function(file, idx) {
            var item = document.createElement('div');
            item.className = 'report-preview-item';
            var img = document.createElement('img');
            img.src = URL.createObjectURL(file);
            img.alt = '预览';
            var rm = document.createElement('span');
            rm.className = 'report-preview-remove';
            rm.textContent = '\u00d7';
            rm.addEventListener('click', function() {
                reportImages.splice(idx, 1);
                renderReportPreview();
            });
            item.appendChild(img);
            item.appendChild(rm);
            area.insertBefore(item, uploadBox);
        });
        var text = document.getElementById('reportUploadText');
        if (text) text.textContent = '上传 ' + reportImages.length + '/4';
        if (reportImages.length >= 4) {
            if (uploadBox) uploadBox.style.display = 'none';
        } else {
            if (uploadBox) uploadBox.style.display = 'flex';
        }
    }
    // 举报图片选择
    var reportImageInput = document.getElementById('reportImageInput');
    var reportUploadBox = document.getElementById('reportUploadBox');
    if (reportUploadBox) {
        reportUploadBox.addEventListener('click', function() {
            if (reportImages.length >= 4) { showToast('最多上传4张图片'); return; }
            if (reportImageInput) reportImageInput.click();
        });
    }
    if (reportImageInput) {
        reportImageInput.addEventListener('change', function() {
            var files = Array.from(this.files);
            var remaining = 4 - reportImages.length;
            files.slice(0, remaining).forEach(function(f) { reportImages.push(f); });
            renderReportPreview();
            this.value = '';
        });
    }

    // 举报原因选择
    document.querySelectorAll('.report-option-btn').forEach(function(btn) {
        btn.addEventListener('click', function() {
            var group = this.parentNode;
            group.querySelectorAll('.report-option-btn').forEach(function(b) {
                b.classList.remove('selected');
            });
            this.classList.add('selected');
            selectedReportReason = this.getAttribute('data-reason');
        });
    });

    // 举报补充说明字数统计
    var reportTextarea = document.getElementById('reportTextarea');
    var reportCharCount = document.getElementById('reportCharCount');
    if (reportTextarea && reportCharCount) {
        reportTextarea.addEventListener('input', function() {
            reportCharCount.textContent = this.value.length;
        });
    }

    // 侧边栏举报按钮
    var sideReportBtn = document.getElementById('sideReportBtn');
    if (sideReportBtn) {
        sideReportBtn.addEventListener('click', function() {
            openReportModal();
        });
    }

    // 更新评论标题计数
    function updateCommentTitleCount(count) {
        var countEl = document.getElementById('commentTitleCount');
        if (countEl) countEl.textContent = count || 0;
    }

    // ========== 打赏（赞赏） ==========
    var tipSelectedAmount = 1;

    function loadTipSummary() {
        apiGet('/content/api/v1/tip/summary?articleId=' + articleId).then(function(res) {
            if (res && res.code === 200 && res.data) {
                var countEl = document.getElementById('tipCount');
                var amountEl = document.getElementById('tipAmount');
                if (countEl) countEl.textContent = res.data.tipCount || 0;
                if (amountEl) amountEl.textContent = res.data.tipAmount || 0;
            }
        }).catch(function(err) { console.error('加载打赏汇总失败:', err); });
    }

    function renderTipList(list) {
        var container = document.getElementById('tipRewardList');
        if (!container) return;
        if (!list || list.length === 0) {
            container.innerHTML = '<div class="tip-reward-list-empty">暂无赞赏，期待你的支持～</div>';
            return;
        }
        var html = '';
        list.forEach(function(item) {
            var avatar = item.avatar || '';
            var name = item.nickName || '匿名用户';
            var msg = item.message || '';
            var amount = item.amount || 0;
            html += '<div class="tip-reward-item">';
            html += '<img class="tip-reward-avatar" src="' + escapeHtml(avatar) + '" alt="avatar" onerror="this.style.visibility=\'hidden\'">';
            html += '<div class="tip-reward-info">';
            html += '<div class="tip-reward-name">' + escapeHtml(name) + '</div>';
            if (msg) html += '<div class="tip-reward-msg">' + escapeHtml(msg) + '</div>';
            html += '</div>';
            html += '<div class="tip-reward-amount">¥' + amount + '</div>';
            html += '</div>';
        });
        container.innerHTML = html;
    }

    function loadTipList() {
        apiGet('/content/api/v1/tip/list?articleId=' + articleId + '&page=1&size=20').then(function(res) {
            if (res && res.code === 200 && res.data) {
                renderTipList(res.data.list);
            }
        }).catch(function(err) { console.error('加载打赏名单失败:', err); });
    }

    function openTipModal() {
        var overlay = document.getElementById('tipModalOverlay');
        if (overlay) overlay.classList.add('open');
        document.body.style.overflow = 'hidden';
        // 重置金额与留言
        tipSelectedAmount = 1;
        document.querySelectorAll('.tip-amount-option').forEach(function(btn) {
            btn.classList.toggle('active', btn.getAttribute('data-amount') === '1');
        });
        document.getElementById('tipAmountCustom').value = '';
        document.getElementById('tipMessage').value = '';
    }
    function closeTipModal() {
        var overlay = document.getElementById('tipModalOverlay');
        if (overlay) overlay.classList.remove('open');
        document.body.style.overflow = '';
    }

    function initTip() {
        var rewardBtn = document.getElementById('tipRewardBtn');
        var closeBtn = document.getElementById('closeTipModal');
        var overlay = document.getElementById('tipModalOverlay');
        var payBtn = document.getElementById('tipPayBtn');
        var customInput = document.getElementById('tipAmountCustom');

        if (rewardBtn) {
            rewardBtn.addEventListener('click', function() {
                if (!isLoggedIn()) { openLoginModal(); return; }
                openTipModal();
            });
        }
        if (closeBtn) closeBtn.addEventListener('click', closeTipModal);
        if (overlay) {
            overlay.addEventListener('click', function(e) {
                if (e.target === overlay) closeTipModal();
            });
        }
        // 金额档位选择
        document.querySelectorAll('.tip-amount-option').forEach(function(btn) {
            btn.addEventListener('click', function() {
                document.querySelectorAll('.tip-amount-option').forEach(function(b) {
                    b.classList.remove('active');
                });
                this.classList.add('active');
                tipSelectedAmount = parseInt(this.getAttribute('data-amount')) || 1;
                if (customInput) customInput.value = '';
            });
        });
        // 自定义金额
        if (customInput) {
            customInput.addEventListener('input', function() {
                var v = this.value;
                if (v) {
                    document.querySelectorAll('.tip-amount-option').forEach(function(b) {
                        b.classList.remove('active');
                    });
                    tipSelectedAmount = parseFloat(v) || 0;
                } else {
                    tipSelectedAmount = 1;
                }
            });
        }
        // 立即赞赏
        if (payBtn) {
            payBtn.addEventListener('click', function() {
                var amount = tipSelectedAmount;
                if (!amount || amount < 1) { showToast('请输入正确的打赏金额'); return; }
                var msg = document.getElementById('tipMessage').value.trim();
                var btn = this;
                btn.disabled = true;
                btn.textContent = '提交中...';
                // articleId 为 19 位雪花 ID，超出 JS Number 安全整数范围（2^53），
                // 必须按字符串传递，否则 JSON 序列化时会丢失精度导致后端匹配不到文章
                apiPost('/content/api/v1/tip/create', { articleId: String(articleId), amount: amount, message: msg }).then(function(res) {
                    btn.disabled = false;
                    btn.textContent = '立即赞赏';
                    if (res && res.code === 200 && res.data && res.data.payUrl) {
                        // 新窗口打开支付页，当前页保持
                        window.open(res.data.payUrl, '_blank');
                        closeTipModal();
                    } else {
                        showToast((res && (res.message || res.errorMessage)) || '创建打赏订单失败');
                    }
                }).catch(function() {
                    btn.disabled = false;
                    btn.textContent = '立即赞赏';
                    showToast('创建打赏订单失败，请重试');
                });
            });
        }
    }

    // ========== 初始化加载 ==========
    initTopBar();
    initLoginModal();
    loadArticleDetail();
    loadColumn();
    loadComments(false);
    loadRelated();
    loadFeatured();
    initTip();
    loadTipSummary();
    loadTipList();
    loadEndAuthorCard();

    // 支付成功回跳（?tip=success）时提示并刷新打赏名单
    (function() {
        var params = new URLSearchParams(window.location.search);
        if (params.get('tip') === 'success') {
            showToast('赞赏成功，感谢你的支持！');
            loadTipSummary();
            loadTipList();
        }
    })();

    // ========== 阅读进度条 ==========
    var readingProgressEl = document.getElementById('readingProgress');
    function updateReadingProgress() {
        if (!readingProgressEl) return;
        var scrollTop = window.pageYOffset || document.documentElement.scrollTop;
        var scrollable = document.documentElement.scrollHeight - window.innerHeight;
        var percent = scrollable > 0 ? Math.min(100, scrollTop / scrollable * 100) : 0;
        readingProgressEl.style.width = percent.toFixed(2) + '%';
    }
    if (readingProgressEl) {
        window.addEventListener('scroll', updateReadingProgress, { passive: true });
        window.addEventListener('resize', updateReadingProgress);
        updateReadingProgress();
    }

    // ========== 阅读偏好（字号/行距/主题/沉浸）持久化 ==========
    var READER_KEY = 'zhuri_reader_settings';
    var DEFAULT_SETTINGS = { fontSize: 17, lineHeight: 1.75, theme: 'light', immersive: false };

    function loadReaderSettings() {
        try {
            var saved = localStorage.getItem(READER_KEY);
            return saved ? Object.assign({}, DEFAULT_SETTINGS, JSON.parse(saved)) : DEFAULT_SETTINGS;
        } catch (e) {
            return DEFAULT_SETTINGS;
        }
    }

    function applyReaderSettings(s) {
        // 字号/行距：写入 html 内联变量，.article-body 自动生效
        document.documentElement.style.setProperty('--read-font-size', s.fontSize + 'px');
        document.documentElement.style.setProperty('--read-line-height', String(s.lineHeight));
        // 主题
        document.body.classList.toggle('dark', s.theme === 'dark');
        // 沉浸
        document.body.classList.toggle('immersive-mode', !!s.immersive);
        // 同步设置面板按钮选中态
        syncSettingButtons(s);
    }

    function saveReaderSettings(s) {
        try { localStorage.setItem(READER_KEY, JSON.stringify(s)); } catch (e) {}
    }

    function syncSettingButtons(s) {
        document.querySelectorAll('.setting-btn[data-font]').forEach(function (b) {
            b.classList.toggle('active', parseInt(b.getAttribute('data-font'), 10) === s.fontSize);
        });
        document.querySelectorAll('.setting-btn[data-line]').forEach(function (b) {
            b.classList.toggle('active', parseFloat(b.getAttribute('data-line')) === s.lineHeight);
        });
        document.querySelectorAll('.setting-btn[data-theme]').forEach(function (b) {
            b.classList.toggle('active', b.getAttribute('data-theme') === s.theme);
        });
    }

    var readerSettings = loadReaderSettings();
    applyReaderSettings(readerSettings);

    // ========== 阅读设置弹窗 ==========
    var readerOverlay = document.getElementById('readerSettingsOverlay');
    function openReaderSettings() {
        if (!readerOverlay) return;
        readerOverlay.classList.add('open');
        document.body.style.overflow = 'hidden';
    }
    function closeReaderSettings() {
        if (!readerOverlay) return;
        readerOverlay.classList.remove('open');
        document.body.style.overflow = '';
    }

    var closeReaderBtn = document.getElementById('closeReaderSettings');
    if (closeReaderBtn) closeReaderBtn.addEventListener('click', closeReaderSettings);
    if (readerOverlay) {
        readerOverlay.addEventListener('click', function (e) {
            if (e.target === readerOverlay) closeReaderSettings();
        });
    }

    // 字号
    document.querySelectorAll('.setting-btn[data-font]').forEach(function (b) {
        b.addEventListener('click', function () {
            readerSettings.fontSize = parseInt(this.getAttribute('data-font'), 10);
            saveReaderSettings(readerSettings);
            applyReaderSettings(readerSettings);
        });
    });
    // 行距
    document.querySelectorAll('.setting-btn[data-line]').forEach(function (b) {
        b.addEventListener('click', function () {
            readerSettings.lineHeight = parseFloat(this.getAttribute('data-line'));
            saveReaderSettings(readerSettings);
            applyReaderSettings(readerSettings);
        });
    });
    // 主题
    document.querySelectorAll('.setting-btn[data-theme]').forEach(function (b) {
        b.addEventListener('click', function () {
            readerSettings.theme = this.getAttribute('data-theme');
            saveReaderSettings(readerSettings);
            applyReaderSettings(readerSettings);
        });
    });
    // 沉浸开关（设置弹窗内）
    var toggleImmersiveBtn = document.getElementById('toggleImmersiveBtn');
    if (toggleImmersiveBtn) {
        toggleImmersiveBtn.addEventListener('click', function () {
            readerSettings.immersive = !readerSettings.immersive;
            saveReaderSettings(readerSettings);
            applyReaderSettings(readerSettings);
            closeReaderSettings();
        });
    }

    // 右侧悬浮栏「设置」入口
    var sideSettingsBtn = document.getElementById('sideSettingsBtn');
    if (sideSettingsBtn) sideSettingsBtn.addEventListener('click', openReaderSettings);

    // ========== 沉浸模式 ==========
    function toggleImmersiveMode() {
        readerSettings.immersive = !readerSettings.immersive;
        saveReaderSettings(readerSettings);
        applyReaderSettings(readerSettings);
    }
    var sideImmersiveBtn = document.getElementById('sideImmersiveBtn');
    if (sideImmersiveBtn) sideImmersiveBtn.addEventListener('click', toggleImmersiveMode);
    var immersiveExitBtn = document.getElementById('immersiveExitBtn');
    if (immersiveExitBtn) immersiveExitBtn.addEventListener('click', toggleImmersiveMode);
})();