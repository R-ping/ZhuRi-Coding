(function() {
    var articleId = window.ARTICLE_ID || '0';
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
                var url = parts[i].trim();
                html += '<img src="' + escapeHtml(url) + '" alt="' + escapeHtml(parts[i - 1]) + '" class="comment-image">';
                continue;
            }
            html += escapeHtml(parts[i]);
        }
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
        if (detailData.isFollow) {
            if (followBtn) { followBtn.classList.add('active'); followBtn.textContent = '已关注'; }
            if (authorFollowBtn) { authorFollowBtn.classList.add('active'); authorFollowBtn.textContent = '已关注'; }
        }
    }

    // ========== 点赞/收藏/关注 API ==========
    var likeBtn = document.getElementById('likeBtn');
    if (likeBtn) {
        likeBtn.addEventListener('click', function() {
            if (!isLoggedIn()) { openLoginModal(); return; }
            var btn = this;
            apiPost('/content/api/v1/article/' + articleId + '/like').then(function(res) {
                if (res && res.code === 200 && res.data) {
                    btn.classList.toggle('active', res.data.liked);
                    document.getElementById('likeBtnText').textContent = res.data.liked ? '已赞' : '点赞';
                    diggCount = res.data.diggCount || 0;
                    updateSidebarCounts();
                    var sideLikeBtn = document.getElementById('sideLikeBtn');
                    sideLikeBtn.classList.toggle('active', res.data.liked);
                }
            }).catch(function(err) { console.error('点赞失败:', err); });
        });
    }

    var collectBtn = document.getElementById('collectBtn');
    if (collectBtn) {
        collectBtn.addEventListener('click', function() {
            if (!isLoggedIn()) { openLoginModal(); return; }
            var btn = this;
            // 打开收藏集选择弹窗
            if (typeof openCollectModal === 'function') {
                openCollectModal();
                return;
            }
            apiPost('/content/api/v1/article/' + articleId + '/collect').then(function(res) {
                if (res && res.code === 200 && res.data) {
                    btn.classList.toggle('active', res.data.collected);
                    document.getElementById('collectBtnText').textContent = res.data.collected ? '已收藏' : '收藏';
                    collectCount = res.data.collectCount || 0;
                    updateSidebarCounts();
                    var sideCollectBtn = document.getElementById('sideCollectBtn');
                    sideCollectBtn.classList.toggle('active', res.data.collected);
                }
            }).catch(function(err) { console.error('收藏失败:', err); });
        });
    }

    function handleFollow() {
        if (!isLoggedIn()) { openLoginModal(); return; }
        apiPost('/content/api/v1/article/' + articleId + '/follow').then(function(res) {
            if (res && res.code === 200 && res.data) {
                var followed = res.data.followed;
                var btns = [document.getElementById('followBtn'), document.getElementById('authorFollowBtn')];
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
    if (followBtn) followBtn.addEventListener('click', handleFollow);
    if (authorFollowBtn) authorFollowBtn.addEventListener('click', handleFollow);

    // 侧边栏点赞/收藏
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
            // 打开收藏集选择弹窗
            if (typeof openCollectModal === 'function') {
                openCollectModal();
                return;
            }
            var cb = document.getElementById('collectBtn');
            if (cb) cb.click();
        });
    }
    var sideCommentBtn = document.getElementById('sideCommentBtn');
    if (sideCommentBtn) {
        sideCommentBtn.addEventListener('click', function() {
            var commentSection = document.getElementById('commentSection');
            if (commentSection) {
                var top = commentSection.getBoundingClientRect().top + window.pageYOffset - 72;
                window.scrollTo({ top: top, behavior: 'smooth' });
            }
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

        // 子回复
        var replies = comment.replyInfos || [];
        if (replies.length > 0) {
            var showReplies = replies.slice(0, 2);
            var hasMoreReplies = replies.length > 2;
            html += '<div class="reply-list">';
            showReplies.forEach(function(reply) {
                var replyUser = reply.userInfo || {};
                // 二级回复项：支持继续回复，携带 parentId(reply.commentId) 与 rootId(一级评论ID)
                html += '<div class="reply-item" data-comment-id="' + reply.commentId + '">';
                html += '<span class="reply-user">' + escapeHtml(replyUser.userName || '匿名') + '：</span>';
                html += renderContent(reply.content);
                html += '<button class="reply-action-btn comment-reply-btn" data-comment-id="' + reply.commentId + '" data-root-id="' + comment.commentId + '">回复</button>';
                html += '</div>';
            });
            html += '</div>';
            if (hasMoreReplies) {
                html += '<button class="reply-more-btn" data-comment-id="' + comment.commentId + '">查看全部 ' + replies.length + ' 条回复</button>';
            }
        }

        li.innerHTML = html;
        return li;
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
        // 简单实现：重新加载评论列表并展开全部
        var commentId = e.currentTarget.getAttribute('data-comment-id');
        // 找到对应的评论项，展开所有回复
        // 在实际场景中需要调用API获取更多回复，这里简化处理
        alert('查看更多回复功能开发中');
    }

    function sendReply() {
        var input = document.getElementById('replyInput');
        var content = buildCommentContent(input.value.trim(), replyImages);
        if (!content) return;
        if (!replyToCommentId) return;
        var body = { content: content };
        if (replyToRootId) {
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
            var content = buildCommentContent(textarea.value.trim(), commentImages);
            if (!content) {
                alert('请输入评论内容');
                return;
            }
            var btn = this;
            btn.disabled = true;
            btn.textContent = '提交中...';
            apiPost('/content/api/v1/comment/article/' + articleId + '/comment', { content: content }).then(function(res) {
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

    // 构建评论提交内容：文本 + 图片（![image](url) 语法）
    function buildCommentContent(text, images) {
        var content = text;
        if (images && images.length > 0) {
            if (content) content += '\n';
            content += images.map(function(u) { return '![image](' + u + ')'; }).join('\n');
        }
        return content;
    }

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

    // ========== 为你推荐 ==========
    var recommendCursor = '';
    var recommendHasMore = false;

    function loadRecommend(append) {
        var url = '/content/api/v1/article/' + articleId + '/recommend?cursor=' + encodeURIComponent(recommendCursor) + '&size=5';
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
                    var li = document.createElement('li');
                    li.className = 'recommend-item';
                    var tags = '';
                    if (item.categoryName) {
                        tags += '<span class="category-tag">' + escapeHtml(item.categoryName) + '</span>';
                    }
                    li.innerHTML = '<div class="recommend-item-title"><a href="/content/article/' + item.articleId + '" target="_blank">' + escapeHtml(item.title) + '</a></div>' +
                        '<div class="recommend-item-meta">' +
                        '<span>' + escapeHtml(item.authorName || '') + '</span>' +
                        '<span class="meta-sep">·</span>' +
                        '<span>' + formatTime(item.publishTime) + '</span>' +
                        '<span class="meta-sep">·</span>' +
                        '<span>' + (item.viewCount || 0) + '阅读</span>' +
                        '<span class="meta-sep">·</span>' +
                        '<span>' + (item.diggCount || 0) + '赞</span>' +
                        '<span class="meta-sep">·</span>' +
                        '<span>' + (item.commentCount || 0) + '评论</span>' +
                        tags +
                        '</div>';
                    container.appendChild(li);
                });
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

    // ========== 收藏集弹窗 ==========
    function openCollectModal() {
        var overlay = document.getElementById('collectModalOverlay');
        if (overlay) overlay.classList.add('open');
        document.body.style.overflow = 'hidden';
    }
    function closeCollectModal() {
        var overlay = document.getElementById('collectModalOverlay');
        if (overlay) overlay.classList.remove('open');
        document.body.style.overflow = '';
    }
    var closeCollectBtn = document.getElementById('closeCollectModal');
    var collectModalOverlay = document.getElementById('collectModalOverlay');
    var collectConfirmBtn = document.getElementById('collectConfirmBtn');
    if (closeCollectBtn) closeCollectBtn.addEventListener('click', closeCollectModal);
    if (collectModalOverlay) {
        collectModalOverlay.addEventListener('click', function(e) {
            if (e.target === collectModalOverlay) closeCollectModal();
        });
    }
    if (collectConfirmBtn) {
        collectConfirmBtn.addEventListener('click', function() {
            alert('收藏成功！');
            closeCollectModal();
        });
    }

    // ========== 举报弹窗 ==========
    var selectedReportReason = '';
    function openReportModal() {
        var overlay = document.getElementById('reportModalOverlay');
        if (overlay) overlay.classList.add('open');
        document.body.style.overflow = 'hidden';
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
    if (confirmReportBtn) {
        confirmReportBtn.addEventListener('click', function() {
            if (!selectedReportReason) {
                alert('请选择举报原因');
                return;
            }
            alert('举报已提交，感谢您的反馈！');
            closeReportModal();
        });
    }

    // 举报原因选择
    document.querySelectorAll('.report-option-btn').forEach(function(btn) {
        btn.addEventListener('click', function() {
            // 同一组内取消其他选择
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

    // ========== 初始化加载 ==========
    initTopBar();
    initLoginModal();
    loadArticleDetail();
    loadColumn();
    loadComments(false);
    loadRecommend(false);
    loadRelated();
    loadFeatured();
})();