// ==================== 应用状态 ====================
const state = {
    currentPage: 'dashboard',
    currentReport: null,
    currentReportId: null,
    currentPageNum: 1,
    currentParams: {},
    currentSortColumn: -1,
    currentSortDirection: 'none',
    totalPages: 0,
    // 仅缓存当前页数据（服务端分页），避免全量进浏览器内存
    pageRows: null,
    totalRows: 0,
    cachedColumns: [],
    pageSize: 100,
    lastQueryParamsKey: '',
    currentUserRole: '',
    currentUsername: '',
    // 落地页标志（由 loadUserInfo 里的 chooseLandingPage 赋值）。
    //   必须**显式初始化**：null = 「还没算」或「一个菜单都进不去」，
    //   两者都由 navigateToLandingPage 兜到「无权限」页，但绝不能是 dashboard，
    //   否则零权限角色会被送进一个已被权限隐藏的空页（见 navigateToLandingPage 注释）。
    landingPage: null,
    currentPermissions: null,
    resetPwdTarget: '',
    defaultDataSourceId: 'default',
    recentReports: JSON.parse(localStorage.getItem('recentReports') || '[]'),
    allowedReportIds: null, // 当前用户有权访问的报表ID集合
    _queryGeneration: 0,
    _querying: false,
    _sqlImportPreview: null,
    _sqlImportConverted: '',
    _sqlImportYaml: ''
};

// ==================== DOM元素 ====================
const elements = {
    sidebar: document.getElementById('sidebar'),
    sidebarToggle: document.getElementById('sidebarToggle'),
    menuBtn: document.getElementById('menuBtn'),
    breadcrumb: document.getElementById('breadcrumb'),
    navItems: document.querySelectorAll('.nav-item'),
    contentPages: document.querySelectorAll('.content-page')
};

// ==================== 工具函数 ====================
function getNextUserId(users) {
    var used = {};
    for (var i = 0; i < users.length; i++) {
        var u = users[i].username;
        if (/^\d{4}$/.test(u)) used[u] = true;
    }
    for (var i = 1; i <= 9999; i++) {
        var id = String(i);
        while (id.length < 4) id = '0' + id;
        if (!used[id]) return id;
    }
    return '';
}

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/**
 * 用于拼接到 onclick="..." 等 HTML 属性内的 JS 字符串字面量。
 * esc() 仅做 HTML 实体转义；在 onclick 属性里 &quot; 会被浏览器解码回 "，
 * 导致 JS 字符串被截断（多层上下文逃逸）。
 * escAttr 用 JSON.stringify 处理 JS 层（引号/反斜杠/换行/</>），
 * 再做 HTML 属性层转义，产出含外层双引号的安全 JS 字符串字面量。
 * 用法：onclick='foo(${escAttr(x)})'  （无需再加引号）
 */
function escAttr(str) {
    if (str == null) return '""';
    var js = JSON.stringify(String(str));
    return js
        .replace(/&/g, '&amp;')
        .replace(/'/g, '&#39;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/**
 * 用于把**数组/对象**（如整行记录、分组清单）拼进 onclick='foo(...)' 这类 HTML 属性。
 * escAttr() 只适用于标量：它产出的是带外层双引号的**字符串字面量**，
 * 数组走它会被压成 "a,b"，调用方拿不到数组（editUserGroups 之类会直接坏掉）。
 * 这里先 JSON.stringify 保留结构，再做 HTML 属性层转义（& < > " '），
 * 浏览器解码属性后正好还原成合法的 JS 数组/对象字面量。
 * 用法：onclick='foo(${escJsonAttr(arr)})'  （无需再加引号）
 */
function escJsonAttr(value) {
    if (value == null) return 'null';
    return String(JSON.stringify(value))
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// 防抖函数
function debounce(fn, delay) {
    let timer = null;
    return function() {
        const ctx = this, args = arguments;
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(ctx, args), delay);
    };
}

// 节流函数
function throttle(fn, delay) {
    let last = 0;
    return function() {
        const now = Date.now();
        if (now - last >= delay) {
            last = now;
            fn.apply(this, arguments);
        }
    };
}

// 密码强度校验
function checkPasswordStrength(pwd) {
    if (pwd.length < 8) return { level: 0, msg: '至少8位' };
    var score = 0;
    if (/[a-z]/.test(pwd)) score++;
    if (/[A-Z]/.test(pwd)) score++;
    if (/[0-9]/.test(pwd)) score++;
    if (/[^a-zA-Z0-9]/.test(pwd)) score++;
    if (pwd.length >= 12) score++;
    var levels = [
        { level: 0, msg: '太弱' },
        { level: 1, msg: '弱' },
        { level: 2, msg: '中等' },
        { level: 3, msg: '强' },
        { level: 4, msg: '很强' }
    ];
    return levels[Math.min(score, 4)];
}

function getTodayStr() {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function getCsrfToken() {
    var m = document.querySelector('meta[name="_csrf"]');
    return m ? m.getAttribute('content') : '';
}

function getCsrfHeader() {
    var m = document.querySelector('meta[name="_csrf_header"]');
    return m ? m.getAttribute('content') : 'X-CSRF-TOKEN';
}

function csrfHeaders(extra) {
    var h = {'Content-Type': 'application/json'};
    h[getCsrfHeader()] = getCsrfToken();
    if (extra) Object.assign(h, extra);
    return h;
}

/**
 * 统一 API 请求：自动 credentials + CSRF（写操作）+ 401/403/MUST_CHANGE 处理
 * @param {string} url
 * @param {object} [options] fetch options；method 默认 GET
 * @returns {Promise<Response>}
 */
async function apiFetch(url, options) {
    options = options || {};
    var method = (options.method || 'GET').toUpperCase();
    var headers = Object.assign({}, options.headers || {});
    if (!headers['Content-Type'] && options.body && typeof options.body === 'string') {
        headers['Content-Type'] = 'application/json';
    }
    if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
        headers[getCsrfHeader()] = getCsrfToken();
    }
    var res = await fetch(url, Object.assign({}, options, {
        method: method,
        headers: headers,
        credentials: options.credentials || 'include'
    }));
    if (res.status === 401) {
        // 会话过期（同账号在别处登录被顶下线）时后端返回 code=SESSION_EXPIRED，跳转带提示的登录页
        var expired = false;
        try {
            var b401 = await res.clone().json();
            expired = !!(b401 && b401.code === 'SESSION_EXPIRED');
        } catch (e) { /* 非 JSON 响应，按普通未登录处理 */ }
        window.location.href = expired ? '/login?expired' : '/login';
        throw new Error('未登录');
    }
    if (res.status === 403) {
        try {
            var body = await res.clone().json();
            if (body && (body.code === 'MUST_CHANGE_PASSWORD' || (body.error && String(body.error).indexOf('修改密码') >= 0))) {
                var modal = document.getElementById('firstLoginModal');
                if (modal) modal.classList.add('active');
                if (typeof showToast === 'function') {
                    showToast('error', body.error || '请先修改密码');
                }
            }
        } catch (e) { /* ignore parse */ }
    }
    return res;
}

// ==================== 键盘快捷键 ====================
document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') {
        var modals = document.querySelectorAll('.modal.active');
        if (modals.length > 0) {
            modals.forEach(function(m) { m.classList.remove('active'); });
        } else if (state.currentPage === 'report-query') {
            navigateTo('reports');
        }
    }
    // Ctrl+Enter 或 Cmd+Enter 触发查询
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        if (state.currentPage === 'report-query' && state.currentReport) {
            e.preventDefault();
            doQuery();
        }
    }
});

// ==================== Toast通知 ====================
/* ==================== 敏感操作按钮的统一反馈 ====================
 * 点击 → 按钮进加载态；成功 → 图标换成对勾并闪一次高光；失败 → 原样恢复。
 * 判据是「这个动作弹过二次确认框」， 删除、启停切换、从 HIS 导入都在这类里。
 *
 * 三条约束都是会被撞到的，不是装饰：
 * 1. **失败绝不显示对勾**。在破坏性操作上给出假成功，比完全没有反馈更糟。
 * 2. 成功后要等动画播完再刷新列表，删除按钮就在被删那一行里，列表一重渲染
 *    它就被销毁，对勾根本来不及出现，这个功能等于没做。等待压在 600ms。
 * 3. 拿不到按钮（键盘 Enter 触发、或调用点忘了传 this）就直接执行，
 *    绝不因为找不到按钮而把操作吞掉。
 *
 * work() 返回真/假表示成败；afterSuccess 一般就是原来的 loadXxxList()。 */
const SENSITIVE_DONE_HOLD_MS = 600;

async function sensitiveFeedback(btn, work, afterSuccess) {
    if (!btn) {
        const ok = await work();
        if (ok && afterSuccess) afterSuccess();
        return ok;
    }
    const original = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span class="loading"></span> 处理中…';
    let ok = false;
    try {
        ok = await work();
    } catch (e) {
        ok = false;   // 错误提示由 work 自己弹，这里只负责把按钮恢复原样
    }
    if (!ok) {
        btn.innerHTML = original;
        btn.disabled = false;
        return false;
    }
    btn.innerHTML = '<i class="ri-check-line"></i> 已完成';
    btn.classList.add('btn-done');
    await new Promise((resolve) => setTimeout(resolve, SENSITIVE_DONE_HOLD_MS));
    btn.classList.remove('btn-done');
    btn.innerHTML = original;
    btn.disabled = false;
    if (afterSuccess) afterSuccess();
    return true;
}

function showToast(type, message) {
    const icons = {
        success: 'ri-checkbox-circle-line',
        error: 'ri-error-warning-line',
        warning: 'ri-error-warning-line',
        info: 'ri-information-line'
    };

    const container = document.getElementById('toastContainer') || document.body;
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    const icon = document.createElement('i');
    icon.className = icons[type] || 'ri-information-line';
    const span = document.createElement('span');
    span.textContent = message;
    toast.appendChild(icon);
    toast.appendChild(span);
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.animation = 'slideOut 0.3s ease forwards';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

// 添加滑出动画
const style = document.createElement('style');
style.textContent = `@keyframes slideOut { to { transform: translateX(100%); opacity: 0; } }`;
document.head.appendChild(style);

// ==================== 导航功能 ====================
function initNavigation() {
    elements.navItems.forEach(item => {
        item.addEventListener('click', function() {
            navigateTo(this.dataset.page);
        });
    });
}

/**
 * 选一个「当前用户真的进得去」的落地页。
 *
 * <p>背景：给「工作台」菜单补上 `data-perm="dashboard.view"` 之后，
 * 一个**零权限**角色会看不到任何菜单。若仍无条件 `navigateTo('dashboard')`，
 * 用户会落在「菜单没有一项高亮、内容被权限隐藏」的页面上 ，
 * 界面既没说为什么，也没说该找谁，用户只会以为系统坏了。
 *
 * <p>做法：按侧栏 **DOM 顺序**找第一个「确实可见」的菜单项。
 * 不写死菜单优先级清单（那又会变成「两处各写一份」），而是**直接问 DOM**：
 * 菜单项的 `display` 是不是 `none`。这样以后加菜单、改 `data-perm` 都不用同步这里。
 *
 * <p>找不到任何可见菜单 → 返回 `null`，由调用方导航到「无权限」落地页。
 *
 * @returns {string|null} 可用的页面标识，或 null（无任何可用菜单）
 */
/**
 * 分组的可见性 = 其子项可见性的「或」：子项全被权限藏掉的分组一起藏掉。
 *
 * <p>必须在 {@code [data-perm]} 那轮显隐**之后**调用，它读的是子项的最终 display。
 * <p>为什么不干脆给父项挂个权限点：一个分组对应好几个权限点（「基础字典」= 社区 + 科室 + 人员），
 * 挂任意一个都会做出「父项看得见、点进去子项全进不去」， 那正是本仓库反复出现的 D3/D8/D10 形状。
 */
function syncNavGroups() {
    document.querySelectorAll('.nav-group').forEach(function (group) {
        var kids = group.querySelectorAll('.nav-item');
        var anyVisible = false;
        for (var i = 0; i < kids.length; i++) {
            if (kids[i].style.display !== 'none') { anyVisible = true; break; }
        }
        group.style.display = anyVisible ? '' : 'none';
    });
}

/** 分组标题点击 = 展开/收起。事件委托，不给每个标题单独挂监听。 */
function initNavGroups() {
    var nav = document.querySelector('.sidebar-nav');
    if (!nav || nav._groupsBound) return;
    nav._groupsBound = true;
    nav.addEventListener('click', function (e) {
        var title = e.target.closest('.nav-group-title');
        if (title) title.parentElement.classList.toggle('open');
    });
}

function chooseLandingPage() {
    var items = document.querySelectorAll('.nav-item[data-page]');
    for (var i = 0; i < items.length; i++) {
        var el = items[i];
        // 只认「带 data-perm 且被判为可见」的菜单；无 data-perm 的菜单（如可视化平台）
        // 不参与落地判断，它们是外链跳转，不是内容页。
        if (!el.dataset.perm) continue;
        if (el.style.display === 'none') continue;
        var page = el.dataset.page;
        if (page && document.getElementById('page-' + page)) return page;
    }
    return null;
}

/**
 * 显示「无权限」落地页：当前角色一个菜单都进不去时使用。
 * 见 {@link chooseLandingPage} 的注释（为什么需要它）。
 */
function showNoPermissionPage() {
    state.currentPage = null;
    // 取消所有菜单高亮，免得停在一个「高亮但进不去」的菜单上，那更让人困惑
    elements.navItems.forEach(function (item) { item.classList.remove('active'); });
    elements.contentPages.forEach(function (p) { p.classList.remove('active'); });
    var target = document.getElementById('page-noperm');
    if (target) target.classList.add('active');

    // 回填账号名，让用户能一眼确认「是当前这个账号权限不足」
    var nameEl = document.getElementById('nopermUsername');
    if (nameEl) nameEl.textContent = state.currentUsername || '—';

    elements.breadcrumb.innerHTML = '<span>暂无可用功能</span>';
}

function navigateTo(page) {
    state.currentPage = page;
    // 更新导航状态
    elements.navItems.forEach(item => {
        item.classList.toggle('active', item.dataset.page === page);
    });
    // 当前页所在的分组自动展开，否则收起分组后跳进子页，侧栏里看不出自己在哪
    const activeGroup = document.querySelector('.nav-item[data-page="' + page + '"]');
    if (activeGroup && activeGroup.closest('.nav-group')) activeGroup.closest('.nav-group').classList.add('open');

    // 更新页面显示
    elements.contentPages.forEach(p => p.classList.remove('active'));
    const targetPage = document.getElementById(`page-${page}`);
    if (targetPage) targetPage.classList.add('active');

    // 更新面包屑
    const pageNames = {
        dashboard: '工作台', reports: '报表中心', 'report-query': '报表查询',
        users: '用户管理', groups: '分组管理', logs: '系统日志', audit: '操作审计',
        reportmanage: '报表管理', communities: '社区管理', departments: '科室管理',
        personnel: '人员管理', roles: '角色管理', affiliation: '归属管理',
        sqlimport: 'SQL导入', datasource: '数据源管理',
        communities: '社区管理', departments: '科室管理', roles: '角色管理',
        personnel: '人员管理', affiliation: '归属管理',
        noperm: '暂无可用功能'
    };
    if (page === 'report-query' && state.currentReport) {
        elements.breadcrumb.innerHTML = `<span style="cursor:pointer;color:var(--primary);" onclick="navigateTo('reports')">报表中心</span><span style="margin:0 8px;color:var(--gray-500);">/</span><span>${esc(state.currentReport.name)}</span>`;
    } else {
        // 分组与名称都从**导航项自己**推导：分组读它所在 .nav-group 的标题，名称读它的显示文字。
        // 原先这里另有一份 pageNames 清单，而它漏了 audit，结果「操作审计」页顶栏
        // 一直显示英文键 `audit`（实测发现）。清单写两份必然漂移，所以以 DOM 为准，
        // pageNames 只留给没有导航项的子页（报表查询、无权限落地页）。
        const nav = document.querySelector('.nav-item[data-page="' + page + '"]');
        const navLabel = nav ? (nav.querySelector('span') || {}).textContent : null;
        const groupEl = nav ? nav.closest('.nav-group') : null;
        const groupTitle = groupEl
            ? ((groupEl.querySelector('.nav-group-title span') || {}).textContent || '').trim() : '';
        // 没进分组的顶级项（工作台 / 报表中心）沿用原来的「查询 / 管理」两段口径
        const group = groupTitle || (nav ? (nav.classList.contains('admin-only') ? '管理' : '查询') : '');
        const label = esc((navLabel && navLabel.trim()) || pageNames[page] || page);
        elements.breadcrumb.innerHTML = group
            ? `<span class="crumb-group">${group}</span><span class="crumb-sep">/</span><span>${label}</span>`
            : `<span>${label}</span>`;
    }

    // 移动端关闭侧边栏
    if (window.innerWidth <= 1024) {
        elements.sidebar.classList.remove('mobile-open');
    }

    // 页面特定加载
    if (page === 'dashboard') loadAnnouncements();
    if (page === 'reports') loadReports();
    else if (page === 'datasource') loadDataSources();
    else if (page === 'users') loadUsersList();
    else if (page === 'groups') loadGroupsList();
    else if (page === 'reportmanage') { loadReportsList(); loadScreenMetrics(); }
    else if (page === 'logs') loadLogsDirect();
    else if (page === 'audit') loadAuditPage();
    else if (page === 'communities') loadCommunitiesList();
    else if (page === 'departments') loadDepartmentsList();
    else if (page === 'roles') loadRolesList();
    else if (page === 'personnel') loadPersonnelList();
    else if (page === 'affiliation' && typeof loadAffiliationPage === 'function') loadAffiliationPage();
}

// ==================== 侧边栏功能 ====================
function initSidebar() {
    // 点击切换固定状态
    elements.sidebarToggle.addEventListener('click', function() {
        const sidebar = elements.sidebar;
        const isCollapsed = sidebar.classList.contains('collapsed');

        if (isCollapsed) {
            // 展开并固定
            sidebar.classList.remove('collapsed');
            sidebar.classList.add('pinned');
            this.querySelector('i').className = 'ri-menu-line';
        } else {
            // 收起并取消固定
            sidebar.classList.add('collapsed');
            sidebar.classList.remove('pinned');
            sidebar.classList.remove('hover-expanded');
            this.querySelector('i').className = 'ri-menu-unfold-line';
        }
    });

    // 鼠标跟随展开收起（仅在非固定状态生效）
    let hoverTimeout = null;

    elements.sidebar.addEventListener('mouseenter', function() {
        if (window.innerWidth <= 1024) return;
        if (this.classList.contains('pinned')) return;

        clearTimeout(hoverTimeout);
        this.classList.add('hover-expanded');
    });

    elements.sidebar.addEventListener('mouseleave', function() {
        if (window.innerWidth <= 1024) return;
        if (this.classList.contains('pinned')) return;

        hoverTimeout = setTimeout(() => {
            this.classList.remove('hover-expanded');
        }, 300); // 300ms延迟，防止误触
    });

    // 移动端菜单
    elements.menuBtn.addEventListener('click', function() {
        elements.sidebar.classList.toggle('mobile-open');
    });

    document.querySelector('.main-content').addEventListener('click', function() {
        if (window.innerWidth <= 1024) elements.sidebar.classList.remove('mobile-open');
    });
}

// ==================== 表格拖拽滚动 ====================
function initTableDragScroll() {
    const tableScroll = document.querySelector('.table-scroll');
    if (!tableScroll) return;

    let isDragging = false;
    let startX, startY, scrollLeft, scrollTop;

    tableScroll.addEventListener('mousedown', (e) => {
        // 忽略表头和链接的点击
        if (e.target.tagName === 'A' || e.target.tagName === 'BUTTON') return;
        if (e.target.closest('thead')) return;

        isDragging = true;
        tableScroll.classList.add('dragging');
        startX = e.pageX - tableScroll.offsetLeft;
        startY = e.pageY - tableScroll.offsetTop;
        scrollLeft = tableScroll.scrollLeft;
        scrollTop = tableScroll.scrollTop;
    });

    tableScroll.addEventListener('mouseleave', () => {
        isDragging = false;
        tableScroll.classList.remove('dragging');
    });

    tableScroll.addEventListener('mouseup', () => {
        isDragging = false;
        tableScroll.classList.remove('dragging');
    });

    tableScroll.addEventListener('mousemove', (e) => {
        if (!isDragging) return;
        e.preventDefault();
        const x = e.pageX - tableScroll.offsetLeft;
        const y = e.pageY - tableScroll.offsetTop;
        const walkX = (x - startX) * 1.5; // 滚动速度倍数
        const walkY = (y - startY) * 1.5;
        tableScroll.scrollLeft = scrollLeft - walkX;
        tableScroll.scrollTop = scrollTop - walkY;
    });
}

// ==================== 退出登录 ====================
function doLogout() {
    var csrfMeta = document.querySelector('meta[name="_csrf"]');
    var csrfInput = document.getElementById('logoutCsrf');
    if (csrfMeta && csrfInput) csrfInput.value = csrfMeta.getAttribute('content');
    var f = document.getElementById('logoutForm');
    if (f) f.submit();
}

// ==================== 加载用户信息 ====================
async function loadUserInfo() {
    try {
        const res = await fetch('/api/admin/current-user', { credentials: 'include' });
        // 会话失效（含被别处顶下线）时必须跳走：原先只判 res.ok，401 直接什么都不做，
        // 于是壳子照渲染 index.html 里的 Admin / 管理员 占位，用户以为是角色权限问题。
        if (res.status === 401) { window.location.href = '/login'; return; }
        if (res.ok) {
            const user = await res.json();
            state.currentUserRole = user.role;
            state.currentUsername = user.username;

            // 侧栏角色名用后端返回的 roleName（角色管理里的名字），
            // 原先写死 'ADMIN' ? '管理员' : '普通用户'，导致自定义角色（如「科主任」）
            // 在侧栏被显示成「普通用户」。roleName 后端一直有返回，只是前端没用。
            const roleLabel = user.roleName || (user.role === 'ADMIN' ? '管理员' : '普通用户');
            const els = {
                userName: user.username,
                userRole: roleLabel,
                userAvatar: user.username.charAt(0).toUpperCase(),
                userAvatarSm: user.username.charAt(0).toUpperCase(),
                userDropdownName: user.username
            };

            Object.entries(els).forEach(([id, val]) => {
                const el = document.getElementById(id);
                if (el) el.textContent = val;
            });

            // 菜单与快捷按钮的显隐：**统一**按后端权限点（permissions），超管返回全部权限点。
            //
            // 原先这里写的是 `if (user.role === 'ADMIN') 把所有 .admin-only 设为可见`，
            // 随后再用 `.nav-item[data-perm]` 把菜单纠正一遍。侧栏因此是对的，
            // 但首页「快捷操作」里的两个按钮（SQL导入 / 用户管理）当时**没有 data-perm**，
            // 只能靠那段 legacy 判定显示，于是同一个功能变成
            // 「侧栏按权限点、首页按 legacy ADMIN」：
            // 任何自定义角色（有 report.manage / users.manage）侧栏进得去、功能真能用，
            // 首页按钮却不出现。而超管（legacy ADMIN）两处都满足，用超管永远测不出来。
            //
            // 现在收敛成一条规则：**凡带 data-perm 的元素都按权限点显示**，
            // 两个按钮也补上 data-perm，与侧栏菜单同源。
            var perms = new Set(user.permissions || []);
            document.querySelectorAll('[data-perm]').forEach(function (el) {
                el.style.display = perms.has(el.dataset.perm) ? '' : 'none';
            });
            state.currentPermissions = perms;
            // 子项显隐定了，才能算分组该不该显示（顺序反了会读到旧值）
            syncNavGroups();

            // 挑一个「当前用户真的进得去」的落地页（见 chooseLandingPage）。
            //   必须在 data-perm 显隐**之后**调用，它要读菜单的实际可见性。
            state.landingPage = chooseLandingPage();
            var btnAnn = document.getElementById('btnAnnouncementNew');
            if (btnAnn) btnAnn.style.display = perms.has('announce.manage') ? '' : 'none';
            // 可视化平台（数据大屏）入口：双通道（角色 screen.view 或工号开关）
            // screenAccess 为后端合并后的最终结果；screenEnabled/perms 兜底兼容旧版接口
            if (user.screenAccess === true || user.screenEnabled === true || perms.has('screen.view')) {
                var navScreen = document.getElementById('navScreen');
                if (navScreen) navScreen.style.display = '';
            }

            // 工作台统计卡片的数字（D10）。
            // 必须**在拿到权限点之后**再决定调哪些接口，原先它写在 index.html 的内联脚本里，
            // 每个用户打开页面都无条件调 /api/admin/datasources（需 datasource.manage），
            // 非管理员因此静默吃 403，「数据源」卡片永远显示 "-"。
            loadDashboardStats(perms);
        }
    } catch (e) {
        console.error('加载用户信息失败:', e);
    }
}

/**
 * 工作台统计卡片：只拉当前用户**有权看**的那几项。
 *
 * <p>判据与侧栏菜单、按钮显隐完全同源，都读 {@code permissions}。
 * 卡片本身也带 {@code data-perm}，这里再判一次是为了**不去发那个请求**：
 * 光把卡片藏起来，接口照样会被调用、照样 403（这正是 D8/D10 的教训）。
 *
 * @param {Set<string>} perms 当前用户权限点集合
 */
async function loadDashboardStats(perms) {
    try {
        // 报表总数：需要 reports.view（或 report.manage，报表管理页也看这个列表）
        if (perms.has('reports.view') || perms.has('report.manage')) {
            const reportsRes = await fetch('/api/reports');
            if (reportsRes.ok) {
                const reports = await reportsRes.json();
                const el = document.getElementById('statReportCount');
                if (el) el.textContent = reports.length;
            }
        }
        // 数据源数：需要 datasource.manage
        if (perms.has('datasource.manage')) {
            const dsRes = await fetch('/api/admin/datasources');
            if (dsRes.ok) {
                const ds = await dsRes.json();
                const dsEl = document.getElementById('statDatasourceCount');
                if (dsEl) dsEl.textContent = ds.length + ' 个';
            }
        }
    } catch (e) {
        console.error('加载统计失败:', e);
    }
}

// ==================== 报表列表 ====================
async function loadReports() {
    try {
        const res = await fetch('/api/reports');
        if (res.status === 401) { window.location.href = '/login'; return; }
        // 403 = 没有 reports.view（D9）。界面入口已按权限隐藏，走到这里只可能是
        // 直接改 URL 或旧缓存页面，静默降级成空列表，不弹错、不把 null 往下传。
        if (!res.ok) {
            state.allowedReportIds = new Set();
            renderReportCards([]);
            renderRecentReports();
            return;
        }
        const reports = await res.json();
        // 缓存当前用户有权访问的报表ID，用于过滤最近访问
        state.allowedReportIds = new Set(reports.map(r => r.id));
        renderReportCards(reports);
        renderRecentReports(); // 刷新最近访问（按权限过滤）
    } catch (e) {
        console.error('加载报表失败:', e);
        showToast('error', '加载报表失败');
    }
}

function renderReportCards(reports) {
    const container = document.getElementById('reportGrid');
    if (!container) return;

    if (reports.length === 0) {
        container.innerHTML = `
            <div class="empty-state" style="grid-column: 1 / -1;">
                <i class="ri-file-chart-line"></i>
                <h3>暂无报表</h3>
                <p>请联系管理员添加报表</p>
            </div>`;
        return;
    }

    const iconColors = ['blue', 'green', 'orange', 'purple'];
    const icons = ['ri-line-chart-line', 'ri-bar-chart-line', 'ri-pie-chart-line', 'ri-funds-line'];
    // 卡片上的管理按钮（编辑SQL / 修改分组）按**权限点**显示，而不是 legacy 的 ADMIN/USER ，
    // 否则只有 report.manage 的自定义角色（「报表管理员」）看不到这些按钮。
    // 权限点尚未加载完时回退到旧判定，保证首屏不闪（超管两条件都满足）。
    const canManageReports = state.currentPermissions
        ? state.currentPermissions.has('report.manage')
        : state.currentUserRole === 'ADMIN';
    const isAdmin = canManageReports;

    container.innerHTML = reports.map((report, index) => {
        const color = iconColors[index % iconColors.length];
        const icon = icons[index % icons.length];
        const paramCount = report.params ? report.params.length : 0;

        const adminActions = isAdmin ? `
            <div class="report-admin-actions">
                <button class="btn-sm" aria-label="编辑报表 SQL" onclick="event.stopPropagation(); editReportSql(${escAttr(report.id)})" title="编辑SQL">
                    <i class="ri-code-line" aria-hidden="true"></i>
                </button>
                <button class="btn-sm" aria-label="隐藏报表 ${esc(report.name)}" onclick="event.stopPropagation(); toggleReportHidden(${escAttr(report.id)}, true)" title="隐藏">
                    <i class="ri-eye-off-line" aria-hidden="true"></i>
                </button>
                <button class="btn-sm" aria-label="修改报表分组" onclick="event.stopPropagation(); changeReportGroup(${escAttr(report.id)})" title="修改分组">
                    <i class="ri-folder-line" aria-hidden="true"></i>
                </button>
                <span class="spacer" aria-hidden="true"></span>
                <button class="btn-sm destructive" aria-label="删除报表 ${esc(report.name)}" onclick="event.stopPropagation(); deleteReport(${escAttr(report.id)}, this)" title="删除">
                    <i class="ri-delete-bin-line" aria-hidden="true"></i>
                </button>
            </div>` : '';

        return `
            <div class="report-card" onclick="selectReport(${escAttr(report.id)})">
                <div class="report-card-header">
                    <div class="report-icon ${color}"><i class="${icon}"></i></div>
                </div>
                <h4>${esc(report.name)}</h4>
                <p>${esc(report.description || '暂无描述')}</p>
                <div class="report-meta">
                    <span><i class="ri-filter-3-line"></i> ${paramCount} 个筛选条件</span>
                </div>
                <div class="report-actions">
                    <button class="btn-sm primary" onclick="event.stopPropagation(); selectReport(${escAttr(report.id)})">
                        <i class="ri-arrow-right-line"></i> 选择
                    </button>
                </div>
                ${adminActions}
            </div>`;
    }).join('');
}

/**
 * 「刷新报表列表」，重新拉取**当前用户可见**的报表。
 *
 * <p>缺陷（D8）：这里原先还会调 `POST /api/reports/refresh`，
 * 而那个端点要求 `report.manage`。但三个「刷新」按钮（顶栏 / 工作台快捷操作 / 报表中心）
 * 是**人人可见**的，于是普通用户 / 医管中心 / 科主任一点就静默吃 403，
 * 更糟的是**前端不检查 `res.ok`，照样弹「报表列表已刷新」**：
 * 同一个按钮对管理员「真刷新」、对其他人「假刷新」，界面完全看不出来。
 *
 * <p>修法：把混在一起的两件事拆开 ，
 * 「重新拉取我可见的列表」（人人可用）与「从磁盘重载报表定义」（管理动作）
 * 本来就是两件事，后者见 {@link reloadReportDefinitions}（只在报表管理页、按权限点显示）。
 */
async function refreshReports() {
    // 只做一件事：重新拉取当前用户可见的报表。
    //
    // 原实现有两条分支，其中一条按 event.target 做按钮动画，但三个调用点
    // （顶栏 / 工作台快捷操作 / 报表中心）都写 `refreshReports()`、**不传 event**，
    // 所以那条分支从来没执行过（死代码），一并删掉。
    try {
        await loadReports();
        showToast('success', '报表列表已刷新');
    } catch (err) {
        showToast('error', '刷新失败: ' + err.message);
    }
}

/**
 * 「重载报表定义」，让服务端重新扫描 SQL 目录（`POST /api/reports/refresh`，需 `report.manage`）。
 *
 * <p>与 {@link refreshReports} 是**两件事**：这个动的是**服务端的报表定义**，
 * 不是「我看到的列表」。所以它单独成一个按钮，只在报表管理页出现、按 `data-perm` 显隐，
 * 并且**检查 `res.ok`**，不再谎报成功。
 */
async function reloadReportDefinitions(e) {
    let btn = null;
    if (e && e.target) btn = e.target.closest('.btn-sm') || e.target.closest('.btn-primary');
    const originalText = btn ? btn.innerHTML : '';
    try {
        if (btn) { btn.innerHTML = '<span class="loading"></span> 重载中...'; btn.disabled = true; }
        const h = {}; h[getCsrfHeader()] = getCsrfToken();
        const res = await fetch('/api/reports/refresh', { method: 'POST', headers: h });
        if (!res.ok) {
            // 不再静默：403 / 500 都要说清楚
            showToast('error', res.status === 403
                ? '权限不足：重载报表定义需要「报表管理」权限'
                : '重载报表定义失败（HTTP ' + res.status + '）');
            return;
        }
        await loadReportsList();
        showToast('success', '报表定义已从磁盘重载');
    } catch (err) {
        showToast('error', '重载失败: ' + err.message);
    } finally {
        if (btn) { btn.innerHTML = originalText; btn.disabled = false; }
    }
}

// ==================== 选择报表 ====================
async function selectReport(id) {
    navigateTo('report-query');
    state.currentReportId = id;

    // 显示加载状态
    const grid = document.getElementById('paramGrid');
    grid.innerHTML = '<div style="display:flex;align-items:center;gap:12px;padding:16px;color:var(--gray-500);"><div class="loading"></div> 加载报表参数...</div>';

    try {
        const res = await fetch('/api/report/' + id);
        if (res.status === 403) {
            showToast('error', '权限不足，无权访问该报表');
            // 从最近访问中移除无权限的报表
            state.recentReports = state.recentReports.filter(r => r.id !== id);
            localStorage.setItem('recentReports', JSON.stringify(state.recentReports));
            renderRecentReports();
            navigateTo('reports');
            return;
        }
        if (!res.ok) { showToast('error', '加载报表失败'); navigateTo('reports'); return; }
        state.currentReport = await res.json();
        state.currentPageNum = 1;
        state.pageRows = null;
        state.totalRows = 0;
        state.cachedColumns = [];
        state.lastQueryParamsKey = '';
        state.currentSortColumn = -1;
        state.currentSortDirection = 'none';

        // 记录最近访问
        addRecentReport(id, state.currentReport.name);

        document.getElementById('resultPanel').classList.remove('show');

        const queryPageTitle = document.getElementById('queryPageTitle');
        if (queryPageTitle) queryPageTitle.textContent = state.currentReport.name;

        const errorEl = document.getElementById('errorMsg');
        if (errorEl) errorEl.classList.remove('show');

        if (!state.currentReport.params || state.currentReport.params.length === 0) {
            grid.innerHTML = '<div style="color:var(--gray-500);font-size:14px;padding:16px;">此报表无需输入条件，直接点击查询即可</div>';
        } else {
            grid.innerHTML = state.currentReport.params.map(renderParam).join('');
            for (const p of state.currentReport.params) {
                if (p.type === 'select' && p.source) loadOptions(id, p.name, p.defaultValue);
            }
            // Enter键触发查询
            grid.querySelectorAll('input, select').forEach(el => {
                el.addEventListener('keydown', function(e) {
                    if (e.key === 'Enter') { e.preventDefault(); doQuery(); }
                });
            });
            const firstInput = grid.querySelector('input, select');
            if (firstInput) firstInput.focus();
        }
    } catch (e) {
        showToast('error', '加载报表失败: ' + e.message);
    }
}

// ==================== 渲染参数 ====================
function renderParam(p) {
    const name = esc(p.name);
    const label = esc(p.label || p.name);
    const req = p.required ? '<span class="required">*</span>' : '';
    const ph = esc(p.placeholder || '');

    let inputHtml = '';

    if (p.name === 'year' || p.name.toLowerCase() === 'year' || p.label === '年度') {
        const currentYear = new Date().getFullYear();
        const defaultYear = p.defaultValue || String(currentYear);
        let options = '<option value="">请选择年份</option>';
        for (let y = currentYear + 1; y >= currentYear - 5; y--) {
            options += `<option value="${y}" ${String(y) === defaultYear ? 'selected' : ''}>${y}年</option>`;
        }
        inputHtml = `<select name="${name}">${options}</select>`;
    }
    else if (p.name === 'month' || p.name.toLowerCase() === 'month' || p.label === '月份') {
        const defaultMonth = p.defaultValue || String(new Date().getMonth() + 1).padStart(2, '0');
        let options = '<option value="">请选择月份</option>';
        for (let m = 1; m <= 12; m++) {
            const val = String(m).padStart(2, '0');
            options += `<option value="${val}" ${val === defaultMonth ? 'selected' : ''}>${m}月</option>`;
        }
        inputHtml = `<select name="${name}">${options}</select>`;
    }
    else if (p.type === 'date') {
        let defVal = p.defaultValue || '';
        if (!defVal || /^\d{4}-01-01$/.test(defVal) || /^\d{4}-01-01T/.test(defVal)) defVal = getTodayStr();
        if (p.name === 'end_date' || p.name === 'endDate' || p.name.endsWith('_end') || p.name.endsWith('EndDate')) defVal = getTodayStr();
        inputHtml = `<input type="date" name="${name}" value="${esc(defVal)}">`;
    } else if (p.type === 'select') {
        inputHtml = `<select name="${name}"><option value="">${ph || '请选择...'}</option></select>`;
    } else if (p.type === 'number') {
        inputHtml = `<input type="number" name="${name}" value="${esc(p.defaultValue || '')}" placeholder="${ph}">`;
    } else {
        inputHtml = `<input type="text" name="${name}" value="${esc(p.defaultValue || '')}" placeholder="${ph}">`;
    }

    return `<div class="param-group"><label>${label} ${req}</label>${inputHtml}</div>`;
}

async function loadOptions(reportId, paramName, defaultVal) {
    try {
        const res = await fetch(`/api/report/${reportId}/options/${encodeURIComponent(paramName)}`);
        if (!res.ok) return;
        const options = await res.json();
        const select = document.querySelector(`select[name="${paramName}"]`);
        if (!select) return;

        options.forEach(opt => {
            const option = document.createElement('option');
            option.value = opt.value;
            option.textContent = opt.label;
            select.appendChild(option);
        });

        // 套用报表 YAML 里声明的默认值。
        //   此前 select 分支不套用 defaultValue（text / number 分支都套了），于是下拉停在
        //   值为空串的占位项上 → 提交空串 → 后端 buildBindings 把空串转成 NULL →
        //   `community LIKE NULL` 恒不成立 → **打开报表直接点查询必然返回 0 条**。
        //   受限的单社区账号反而看不出来：后端 resolveCommunities 会替它回填具体社区。
        if (defaultVal != null && defaultVal !== '') {
            const hit = Array.from(select.options).find(o => o.value === String(defaultVal));
            if (hit) select.value = String(defaultVal);
        }
    } catch (e) {
        console.warn('加载选项失败:', paramName, e);
    }
}

// ==================== 单元格复制 ====================
function copyCellContent(td) {
    const text = td.textContent;
    if (!text) return;
    if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(() => {
            showToast('success', '已复制');
        }).catch(() => {
            fallbackCopy(text);
        });
    } else {
        fallbackCopy(text);
    }
}

function fallbackCopy(text) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;left:-9999px;';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); showToast('success', '已复制'); }
    catch(e) { showToast('error', '复制失败'); }
    document.body.removeChild(ta);
}

// ==================== 最近访问 ====================
function addRecentReport(id, name) {
    state.recentReports = state.recentReports.filter(r => r.id !== id);
    state.recentReports.unshift({ id, name, time: Date.now() });
    if (state.recentReports.length > 5) state.recentReports = state.recentReports.slice(0, 5);
    localStorage.setItem('recentReports', JSON.stringify(state.recentReports));
    renderRecentReports();
}

function renderRecentReports() {
    const tbody = document.getElementById('recentReports');
    if (!tbody) return;
    // 按权限过滤最近访问：只显示当前用户有权访问的报表
    let visible = state.recentReports;
    if (state.allowedReportIds) {
        visible = visible.filter(r => state.allowedReportIds.has(r.id));
    }
    if (visible.length === 0) {
        tbody.innerHTML = '<tr><td colspan="2" style="text-align:center;color:var(--gray-500);padding:40px;">请从左侧选择报表开始查询</td></tr>';
        return;
    }
    tbody.innerHTML = visible.map(r => `
        <tr>
            <td><i class="ri-file-chart-line" style="color:var(--primary);margin-right:8px;"></i>${esc(r.name)}</td>
            <td>
                <button class="btn-sm primary" onclick="selectReport(${escAttr(r.id)})">
                    <i class="ri-arrow-right-line"></i> 查询
                </button>
            </td>
        </tr>`).join('');
}

// ==================== 查询功能 ====================
function collectParams() {
    const params = {};
    if (state.currentReport && state.currentReport.params) {
        state.currentReport.params.forEach(p => {
            const el = document.querySelector(`[name="${p.name}"]`);
            if (el) params[p.name] = el.value;
        });
    }
    return params;
}

function getParamsKey(params) {
    return JSON.stringify(params, Object.keys(params).sort());
}

async function doQuery(page) {
    if (!state.currentReport) return;
    page = Math.max(1, parseInt(page, 10) || 1);

    // 防重复点击
    if (state._querying) return;
    state._querying = true;

    const params = collectParams();
    const paramsKey = getParamsKey(params);
    const paramsChanged = paramsKey !== state.lastQueryParamsKey;

    // 参数变化时从第 1 页重新查，并清除排序状态
    if (paramsChanged) {
        page = 1;
        state.currentSortColumn = -1;
        state.currentSortDirection = 'none';
    }

    state.currentParams = params;
    state.currentPageNum = page;
    state.lastQueryParamsKey = paramsKey;

    // 递增查询代数，用于丢弃过期响应
    const generation = ++state._queryGeneration;

    document.getElementById('resultPanel').classList.add('show');
    document.getElementById('loadingState').classList.add('show');
    document.getElementById('tableArea').style.display = 'none';
    document.getElementById('paginationBar').innerHTML = '';
    document.getElementById('statsBar').innerHTML = '';
    document.getElementById('errorMsg').classList.remove('show');

    const queryBtn = document.getElementById('queryBtn');
    const exportBtn = document.getElementById('exportBtn');
    if (queryBtn) queryBtn.disabled = true;
    if (exportBtn) exportBtn.disabled = true;

    try {
        // 服务端分页：只取当前页，浏览器仅保留 pageRows
        const res = await apiFetch(`/api/report/${state.currentReport.id}/query`, {
            method: 'POST',
            body: JSON.stringify({
                params: params,
                page: page,
                page_size: state.pageSize
            })
        });

        // 丢弃过期响应
        if (generation !== state._queryGeneration) return;

        const data = await res.json();

        if (data.error) {
            showError(data.error);
            return;
        }

        if (data.truncated) {
            showToast('warning', '结果数据量超过服务端上限（20000 行），已截断，可能不完整');
        }

        state.pageRows = data.rows || [];
        state.cachedColumns = data.columns || [];
        state.totalRows = typeof data.total === 'number' ? data.total : state.pageRows.length;
        state.currentPageNum = data.page || page;
        if (data.pageSize) state.pageSize = data.pageSize;
        renderPage(state.currentPageNum);
    } catch (e) {
        if (generation !== state._queryGeneration) return;
        showError('请求失败: ' + e.message);
    } finally {
        if (generation === state._queryGeneration) {
            document.getElementById('loadingState').classList.remove('show');
            if (queryBtn) queryBtn.disabled = false;
            if (exportBtn) exportBtn.disabled = false;
            state._querying = false;
        }
    }
}

function changePageSize(val) {
    state.pageSize = Math.min(500, Math.max(1, parseInt(val, 10) || 100));
    // 改页大小后回第 1 页并重新请求服务端
    doQuery(1);
}

function renderPage(page) {
    if (!state.pageRows || !state.cachedColumns) return;

    var total = state.totalRows || 0;
    state.totalPages = Math.ceil(total / state.pageSize) || 1;
    if (page < 1) page = 1;
    if (page > state.totalPages) page = state.totalPages;
    state.currentPageNum = page;

    document.getElementById('resultInfo').textContent = '共 ' + total.toLocaleString() + ' 条记录（服务端分页）';

    // 更新排序指示（仅当前页）
    var thead = document.getElementById('tableHead');
    thead.innerHTML = '<tr>' + state.cachedColumns.map(function(c, index) {
        var sortClass = '';
        if (index === state.currentSortColumn) {
            sortClass = state.currentSortDirection === 'asc' ? ' sort-asc' : ' sort-desc';
        }
        return '<th data-sort="true" onclick="sortTable(' + index + ')" class="sortable' + sortClass + '" title="仅对当前页排序">' + esc(c) + '</th>';
    }).join('') + '</tr>';

    const tbody = document.getElementById('tableBody');
    if (state.pageRows.length === 0) {
        tbody.innerHTML = `<tr><td colspan="${Math.max(state.cachedColumns.length, 1)}" style="text-align:center;color:var(--gray-500);padding:60px;">
            <div style="font-size:48px;margin-bottom:16px;"><i class="ri-inbox-line"></i></div>
            <div>暂无数据</div>
        </td></tr>`;
    } else {
        tbody.innerHTML = state.pageRows.map(row =>
            '<tr>' + row.map(cell => `<td title="点击复制" onclick="copyCellContent(this)" style="cursor:pointer;">${esc(cell)}</td>`).join('') + '</tr>'
        ).join('');
    }

    document.getElementById('tableArea').style.display = 'flex';
    updateStats();
    renderPagination(page, state.totalPages, total);
}

function renderPagination(page, totalPages, total) {
    const paginationEl = document.getElementById('paginationBar');

    if (totalPages <= 1) {
        paginationEl.innerHTML = `<span class="pagination-info">共 ${total.toLocaleString()} 条记录</span>`;
        return;
    }

    let btns = '';
    btns += `<button class="page-btn" onclick="doQuery(${page - 1})" ${page <= 1 ? 'disabled' : ''}><i class="ri-arrow-left-s-line"></i></button>`;

    // 智能分页：最多显示7个页码按钮
    var maxVisible = 7;
    var start, end;
    if (totalPages <= maxVisible) {
        start = 1; end = totalPages;
    } else {
        start = Math.max(1, page - 3);
        end = Math.min(totalPages, page + 3);
        if (start <= 1) { start = 1; end = maxVisible; }
        if (end >= totalPages) { end = totalPages; start = totalPages - maxVisible + 1; }
    }

    if (start > 1) {
        btns += `<button class="page-btn" onclick="doQuery(1)">1</button>`;
        if (start > 2) btns += `<span class="page-info">...</span>`;
    }

    for (var i = start; i <= end; i++) {
        btns += `<button class="page-btn ${i === page ? 'active' : ''}" onclick="doQuery(${i})">${i}</button>`;
    }

    if (end < totalPages) {
        if (end < totalPages - 1) btns += `<span class="page-info">...</span>`;
        btns += `<button class="page-btn" onclick="doQuery(${totalPages})">${totalPages}</button>`;
    }

    btns += `<button class="page-btn" onclick="doQuery(${page + 1})" ${page >= totalPages ? 'disabled' : ''}><i class="ri-arrow-right-s-line"></i></button>`;

    // 跳页输入框
    btns += `<span class="page-jump">跳到<input type="number" id="jumpPageInput" min="1" max="${totalPages}" value="${page}" onkeydown="if(event.key==='Enter'){doQuery(parseInt(this.value))}">页</span>`;

    paginationEl.innerHTML = `
        <span class="pagination-info">第 ${page}/${totalPages} 页，共 ${total.toLocaleString()} 条</span>
        <div class="pagination-btns">${btns}</div>`;
}

function updateStats() {
    const statsEl = document.getElementById('statsBar');
    if (!statsEl || !state.pageRows) return;

    const total = state.totalRows || 0;
    const page = state.currentPageNum;
    const pageCount = state.pageRows.length;
    const startRow = total === 0 ? 0 : (page - 1) * state.pageSize + 1;
    const endRow = total === 0 ? 0 : Math.min((page - 1) * state.pageSize + pageCount, total);

    statsEl.innerHTML = `
        <div class="stat-item"><i class="ri-bar-chart-line"></i><span>总记录:</span><span class="stat-value">${total.toLocaleString()}</span></div>
        <div class="stat-item"><i class="ri-file-list-3-line"></i><span>列数:</span><span class="stat-value">${state.cachedColumns.length}</span></div>
        <div class="stat-item"><i class="ri-pages-line"></i><span>当前页:</span><span class="stat-value">${startRow}-${endRow}</span></div>`;
}

// ==================== 表格排序（仅当前页，全量请导出后处理） ====================
function sortTable(columnIndex) {
    if (!state.pageRows || state.pageRows.length === 0) return;

    if (state.currentSortColumn === columnIndex) {
        state.currentSortDirection = state.currentSortDirection === 'asc' ? 'desc' : 'asc';
    } else {
        state.currentSortDirection = 'asc';
        state.currentSortColumn = columnIndex;
    }

    const headers = document.querySelectorAll('#resultTable th');
    headers.forEach((header, index) => {
        header.classList.remove('sort-asc', 'sort-desc');
        if (index === columnIndex) {
            header.classList.add(state.currentSortDirection === 'asc' ? 'sort-asc' : 'sort-desc');
        }
    });

    state.pageRows.sort((a, b) => {
        const aVal = (a[columnIndex] || '').trim();
        const bVal = (b[columnIndex] || '').trim();
        const aNum = parseFloat(aVal.replace(/[^0-9.-]/g, ''));
        const bNum = parseFloat(bVal.replace(/[^0-9.-]/g, ''));

        if (!isNaN(aNum) && !isNaN(bNum)) {
            return state.currentSortDirection === 'asc' ? aNum - bNum : bNum - aNum;
        }
        return state.currentSortDirection === 'asc' ?
            aVal.localeCompare(bVal, 'zh-CN') : bVal.localeCompare(aVal, 'zh-CN');
    });

    renderPage(state.currentPageNum);
    showToast('info', `已按${state.currentSortDirection === 'asc' ? '升序' : '降序'}排列（仅当前页）`);
}

// ==================== 导出功能 ====================
async function doExport() {
    if (!state.currentReport) return;
    // 导出需要 report.export，且**必须在发请求之前**判。
    //   光把按钮藏起来不够：接口照样会被调用、照样 403（D8/D10 的教训）。
    //   「导出Excel」是独立权限点，管理员完全可能给出「能看报表、但不许导出」的角色；
    //   原先这个按钮既没带 data-perm、这里也不判，于是那种角色点下去就是一句
    //   语焉不详的「导出失败」（D13）。
    if (state.currentPermissions && !state.currentPermissions.has('report.export')) {
        showError('你没有「导出Excel」权限，请联系管理员开通');
        return;
    }
    if (state._exporting) return;
    state._exporting = true;

    const params = collectParams();
    const btn = document.getElementById('exportBtn');
    const originalContent = btn.innerHTML;

    btn.disabled = true;
    btn.innerHTML = '<span class="loading"></span> 导出中...';

    try {
        const res = await fetch(`/api/report/${state.currentReport.id}/export`, {
            method: 'POST',
            headers: csrfHeaders(),
            body: JSON.stringify({ params: params })
        });
        if (res.status === 401) { window.location.href = '/login'; return; }
        if (!res.ok) {
            let errMsg = '导出失败';
            try {
                const errData = await res.json();
                errMsg = errData.error || errMsg;
            } catch { errMsg = await res.text() || errMsg; }
            showError(errMsg);
            return;
        }

        const truncated = res.headers.get('X-Report-Truncated') === 'true';

        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = state.currentReport.name + '.xlsx';
        a.click();
        URL.revokeObjectURL(url);

        if (truncated) {
            showToast('warning', '导出数据超过 20000 行，已截断，请在 Excel 末尾查看提示');
        } else {
            showToast('success', '导出成功');
        }
    } catch (e) {
        showError('导出失败: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalContent;
        state._exporting = false;
    }
}

// ==================== 错误显示 ====================
function showError(msg) {
    const el = document.getElementById('errorMsg');
    if (el) {
        el.querySelector('.error-text').textContent = msg;
        el.classList.add('show');
    }
}

// ==================== 用户管理 ====================
async function deleteUser(username, btn) {
    if (!confirm(`注意：确定要删除用户 "${username}" 吗？\n\n此操作不可恢复！`)) return;
    await sensitiveFeedback(btn, async () => {
        try {
            const res = await fetch(`/api/admin/users/${encodeURIComponent(username)}`, {
                method: 'DELETE',
                headers: csrfHeaders()
            });
            if (res.ok) {
                showToast('success', '用户已删除');
                return true;
            }
            showToast('error', '删除失败');
            return false;
        } catch (e) {
            showToast('error', '删除失败: ' + e.message);
            return false;
        }
    }, loadUsersList);
}

// ==================== 密码框通用交互（显示/隐藏 + 两次一致性） ====================
/**
 * 把某个密码框在「密文 / 明文」之间切换。
 *
 * <p>为什么需要：首次登录强制改密是用户**第一次**见到本系统的界面，
 * 三个框全是密文，用户无法确认自己有没有敲错（尤其「确认新密码」输错时
 * 只剩一句 toast，得整个重来）。眼睛按钮让他能自己看一眼。
 *
 * <p>按钮用 `data-target="<inputId>"` 关联输入框，所以探针可以直接
 * `togglePwdVisibility('firstNewPwd')` 调它，不必伪造点击事件。
 */
function togglePwdVisibility(inputId) {
    const input = document.getElementById(inputId);
    if (!input) return;
    setPwdVisible(inputId, input.type === 'password');
}

/** 显式设置明文/密文；同时把按钮的图标、aria-label、aria-pressed 对齐。 */
function setPwdVisible(inputId, visible) {
    const input = document.getElementById(inputId);
    if (!input) return;
    input.type = visible ? 'text' : 'password';
    const btn = document.querySelector('.pwd-toggle[data-target="' + inputId + '"]');
    if (!btn) return;
    const label = visible ? '隐藏密码' : '显示密码';
    btn.setAttribute('aria-label', label);
    btn.setAttribute('title', label);
    btn.setAttribute('aria-pressed', visible ? 'true' : 'false');
    const icon = btn.querySelector('i');
    if (icon) icon.className = visible ? 'ri-eye-off-line' : 'ri-eye-line';
}

/** 打开弹窗时把一组密码框复位成密文，否则上次看过的那条明文会留在界面上。 */
function resetPwdVisibility(inputIds) {
    (inputIds || []).forEach(function (id) { setPwdVisible(id, false); });
}

/**
 * 「两次输入的密码是否一样」的实时提示。
 *
 * <p>只在**确认框非空**时才给结论：一进页面就飘红「不一致」是噪音，
 * 而用户边输边看，才能在按提交之前就发现敲错。
 */
function updatePwdMatchHint(newId, confirmId, hintId) {
    const newEl = document.getElementById(newId);
    const confirmEl = document.getElementById(confirmId);
    const hint = document.getElementById(hintId);
    if (!newEl || !confirmEl || !hint) return;
    hint.classList.remove('ok', 'bad');
    if (!confirmEl.value) { hint.textContent = ''; return; }
    if (newEl.value === confirmEl.value) {
        hint.innerHTML = '<i class="ri-check-line" aria-hidden="true"></i> 两次输入一致';
        hint.classList.add('ok');
    } else {
        hint.innerHTML = '<i class="ri-error-warning-line" aria-hidden="true"></i> 两次输入的密码不一致';
        hint.classList.add('bad');
    }
}

/** 清空一致性提示（打开弹窗时调用，避免上次的结论残留）。 */
function clearPwdMatchHint(hintId) {
    const hint = document.getElementById(hintId);
    if (!hint) return;
    hint.classList.remove('ok', 'bad');
    hint.textContent = '';
}

// ==================== 修改密码 ====================
function showChangePassword() {
    document.getElementById('changePwdUsername').value = state.currentUsername;
    document.getElementById('changePwdOld').value = '';
    document.getElementById('changePwdNew').value = '';
    document.getElementById('changePwdConfirm').value = '';
    // 复位：三个框回到密文、清掉上次的一致性结论
    resetPwdVisibility(['changePwdOld', 'changePwdNew', 'changePwdConfirm']);
    clearPwdMatchHint('changePwdMatchHint');
    document.getElementById('passwordModal').classList.add('active');
}

function hideChangePassword() {
    document.getElementById('passwordModal').classList.remove('active');
}

async function submitChangePassword() {
    const oldPwd = document.getElementById('changePwdOld').value;
    const newPwd = document.getElementById('changePwdNew').value;
    const confirmPwd = document.getElementById('changePwdConfirm').value;

    if (!oldPwd) {
        showToast('error', '请输入当前密码');
        return;
    }

    if (!newPwd || newPwd.length < 8) {
        showToast('error', '新密码至少8位，包含字母和数字');
        return;
    }
    var pwdStrength = checkPasswordStrength(newPwd);
    if (pwdStrength.level < 2) {
        showToast('error', '密码太弱，需包含字母和数字');
        return;
    }

    if (newPwd !== confirmPwd) {
        // 除了 toast，也把结论钉在确认框下面（toast 几秒就没了，用户回看不到是哪个框错的）
        updatePwdMatchHint('changePwdNew', 'changePwdConfirm', 'changePwdMatchHint');
        document.getElementById('changePwdConfirm').focus();
        showToast('error', '两次密码不一致');
        return;
    }

    try {
        const res = await fetch('/api/admin/profile/password', {
            method: 'PUT',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({ oldPassword: oldPwd, password: newPwd })
        });

        if (res.ok) {
            showToast('success', '密码已修改');
            hideChangePassword();
        } else {
            showToast('error', '修改失败');
        }
    } catch (e) {
        showToast('error', '修改失败: ' + e.message);
    }
}

// ==================== 分组管理 ====================
function hideGroupPanel() {
    document.getElementById('groupModal').classList.remove('active');
}

async function loadGroups() {
    try {
        const res = await fetch('/api/admin/groups', { credentials: 'include' });
        if (!res.ok) { showToast('error', '加载分组失败'); return; }
        const groups = await res.json();
        renderGroupList(groups);
    } catch (e) {
        showToast('error', '加载分组失败');
    }
}

function renderGroupList(groups) {
    const wrap = document.getElementById('groupListWrap');
    if (!wrap) return;

    wrap.innerHTML = `
        <ul class="group-list">
            ${groups.map(g => `
                <li class="group-item">
                    <div class="group-info">
                        <div class="group-icon"><i class="ri-folder-line"></i></div>
                        <div>
                            <div class="group-name">${esc(g.name)}</div>
                            <div class="group-id">${esc(g.id)}</div>
                        </div>
                        <div class="group-sort">排序: ${g.sort || 1}</div>
                    </div>
                    <div class="group-actions">
                        <button class="action-btn danger" onclick="deleteGroup(${escAttr(g.id)}, this)">删除</button>
                    </div>
                </li>`).join('')}
        </ul>`;
}

async function createGroup() {
    const id = document.getElementById('newGroupId').value;
    const name = document.getElementById('newGroupName').value;
    const sort = document.getElementById('newGroupSort').value;

    if (!id || !name) {
        showToast('error', '请填写分组ID和名称');
        return;
    }

    try {
        const res = await fetch('/api/admin/groups', {
            method: 'POST',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({ id, name, sort: parseInt(sort) || 1 })
        });

        if (res.ok) {
            showToast('success', '分组创建成功');
            document.getElementById('newGroupId').value = '';
            document.getElementById('newGroupName').value = '';
            loadGroups();
        } else {
            const data = await res.json();
            showToast('error', data.error || '创建失败');
        }
    } catch (e) {
        showToast('error', '创建失败: ' + e.message);
    }
}

async function deleteGroup(groupId, btn) {
    if (!confirm(`注意：确定要删除分组 "${groupId}" 吗？\n\n该分组下的报表将变为无分组状态！`)) return;
    await sensitiveFeedback(btn, async () => {
        try {
            const res = await fetch(`/api/admin/groups/${encodeURIComponent(groupId)}`, {
                method: 'DELETE',
                headers: csrfHeaders()
            });
            if (res.ok) {
                showToast('success', '分组已删除');
                return true;
            }
            showToast('error', '删除失败');
            return false;
        } catch (e) {
            showToast('error', '删除失败: ' + e.message);
            return false;
        }
    }, loadGroups);
}

// ==================== SQL导入 ====================
function showSqlImport() {
    document.getElementById('sqlImportModal').classList.add('active');
    loadGroupsForImport();
}

function hideSqlImport() {
    document.getElementById('sqlImportModal').classList.remove('active');
}

async function loadGroupsForImport() {
    try {
        // 这里只是「选个分组」的下拉 → 用字典端点，不要用需要 groups.manage 的管理接口
        const res = await fetch('/api/admin/groups/options', { credentials: 'include' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const groups = await res.json();
        const select = document.getElementById('sqlImportGroup');
        if (select) {
            select.innerHTML = groups.map(g => `<option value="${esc(g.id)}">${esc(g.name)}</option>`).join('');
        }
    } catch (e) {
        console.error('加载分组失败:', e);
    }
}

async function previewSqlImport() {
    const input = document.getElementById('sqlImportInput').value;
    if (!input.trim()) {
        showToast('error', '请粘贴SQL语句');
        return;
    }
    const name = (document.getElementById('sqlImportName') && document.getElementById('sqlImportName').value) || '未命名报表';
    const desc = (document.getElementById('sqlImportDesc') && document.getElementById('sqlImportDesc').value) || '';

    try {
        const res = await fetch('/api/admin/sql/preview', {
            method: 'POST',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({ sql: input, name: name, description: desc })
        });
        const data = await res.json().catch(() => ({}));
        const notesEl = document.getElementById('sqlImportNotes');
        // 转换说明/警告（错误时也展示已完成的处理步骤）
        const renderNotes = () => {
            let h = '';
            (data.notes || []).forEach(n => {
                h += `<div style="font-size:12px;color:var(--primary);margin-bottom:2px;"><i class="ri-check-line"></i> ${esc(n)}</div>`;
            });
            (data.warnings || []).forEach(w => {
                h += `<div style="font-size:12px;color:var(--warning,#e6a23c);margin-bottom:2px;"><i class="ri-alert-line"></i> ${esc(w)}</div>`;
            });
            notesEl.innerHTML = h || '';
        };
        if (!res.ok) {
            renderNotes();
            document.getElementById('btnSqlTest').disabled = true;
            document.getElementById('btnSqlSave').disabled = true;
            showToast('error', data.error || '预览失败');
            if (data.error) {
                document.getElementById('sqlImportResult').innerHTML =
                    `<div style="color:var(--danger);font-size:13px;"><i class="ri-error-warning-line"></i> ${esc(data.error)}</div>`;
            }
            return;
        }
        renderNotes();
        state._sqlImportConverted = data.convertedSql || input;
        state._sqlImportPreview = data.params || [];
        state._sqlImportYaml = data.yaml || '';

        const paramsEl = document.getElementById('sqlImportParams');
        const params = state._sqlImportPreview;
        if (params.length > 0) {
            paramsEl.innerHTML = `
                <div class="param-preview-list">
                    ${params.map(p => `
                        <span class="param-preview-tag">
                            ${esc(p.label || p.name || p)}
                            <span class="param-type">${esc(p.name || '')}${p.type ? ' / ' + esc(p.type) : ''}</span>
                        </span>`).join('')}
                </div>`;
        } else {
            paramsEl.innerHTML = '<div style="color:var(--gray-500);">未检测到参数</div>';
        }

        document.getElementById('sqlImportPreview').textContent =
            (state._sqlImportYaml || '') + (state._sqlImportConverted || '');
        document.getElementById('btnSqlTest').disabled = false;
        document.getElementById('btnSqlSave').disabled = false;
        showToast('success', '转换预览完成');
    } catch (e) {
        showToast('error', '预览失败: ' + e.message);
    }
}

function clearSqlImport() {
    document.getElementById('sqlImportInput').value = '';
    document.getElementById('sqlImportNotes').innerHTML = '';
    document.getElementById('sqlImportParams').innerHTML = '粘贴SQL后点击"预览转换"';
    document.getElementById('sqlImportPreview').textContent = '粘贴SQL后点击"预览转换"';
    document.getElementById('btnSqlTest').disabled = true;
    document.getElementById('btnSqlSave').disabled = true;
    document.getElementById('sqlTestResult').innerHTML = '';
    document.getElementById('sqlImportResult').innerHTML = '';
    state._sqlImportPreview = null;
    state._sqlImportConverted = '';
}

async function testSqlImport() {
    if (!state._sqlImportConverted) {
        showToast('error', '请先预览转换');
        return;
    }
    try {
        const res = await fetch('/api/admin/sql/test', {
            method: 'POST',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({ sql: state._sqlImportConverted })
        });
        const data = await res.json().catch(() => ({}));
        const resultEl = document.getElementById('sqlTestResult');
        if (res.ok) {
            const cols = data.columns || [];
            const rows = data.rows || [];
            let html = `<div style="margin-bottom:8px;color:var(--success);">测试成功：${rows.length} 行 / ${esc(data.duration || '')}</div>`;
            if (cols.length) {
                html += '<div style="overflow:auto;max-height:240px;"><table class="data-table"><thead><tr>'
                    + cols.map(c => `<th>${esc(c)}</th>`).join('')
                    + '</tr></thead><tbody>'
                    + rows.map(r => '<tr>' + (r || []).map(cell => `<td>${esc(cell)}</td>`).join('') + '</tr>').join('')
                    + '</tbody></table></div>';
            }
            if (resultEl) resultEl.innerHTML = html;
            showToast('success', 'SQL 测试通过');
        } else {
            if (resultEl) resultEl.innerHTML = `<div style="color:var(--danger);">${esc(data.error || '测试失败')}</div>`;
            showToast('error', data.error || '测试失败');
        }
    } catch (e) {
        showToast('error', '测试失败: ' + e.message);
    }
}

async function executeSqlImport() {
    const name = document.getElementById('sqlImportName').value;
    const desc = document.getElementById('sqlImportDesc').value;
    const group = document.getElementById('sqlImportGroup').value;

    if (!name) {
        showToast('error', '请填写报表名称');
        return;
    }

    if (!state._sqlImportConverted) {
        showToast('error', '请先预览转换');
        return;
    }

    try {
        // 与后端 /api/admin/sql/import 契约一致：传 name/description/sql/groupId，由服务端生成 YAML
        const res = await fetch('/api/admin/sql/import', {
            method: 'POST',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({
                name: name,
                description: desc || '',
                groupId: group || 'default',
                sql: state._sqlImportConverted
            })
        });

        if (res.ok) {
            const data = await res.json().catch(() => ({}));
            showToast('success', data.message || '报表导入成功');
            clearSqlImport();
            hideSqlImport();
            loadReports();
        } else {
            const data = await res.json().catch(() => ({}));
            showToast('error', data.error || '导入失败');
        }
    } catch (e) {
        showToast('error', '导入失败: ' + e.message);
    }
}

// 表格加载态：一行骨架，保持"表格已在、数据在路上"的轮廓。
// 刻意只占一行，各渲染套件用「行数 > 1」判定"真的画出了数据"，
// 骨架若占多行会让门禁在加载态就放行。
function skeletonRow(cols) {
    return '<tr><td colspan="' + cols + '" class="cell-empty">'
        + '<div class="sk-stack"><div class="sk-line w-3"></div>'
        + '<div class="sk-line w-2"></div><div class="sk-line w-1"></div></div>'
        + '</td></tr>';
}

// ==================== 用户管理 - 直接加载 ====================
var _usersAll = [];       // 全量用户（搜索在前端过滤这份数组）
var _roleNameMap = {};    // 角色 id → 角色名

async function loadUsersList() {
    const tbody = document.getElementById('userTableBody');
    if (!tbody) return;
    tbody.innerHTML = skeletonRow(7);

    try {
        // 本页只显示 user.groups（分组 id），不需要分组字典 ，
        // 原先这里白调了一次 /api/admin/groups（需 groups.manage），返回的变量从未被使用，
        // 既浪费一次限流配额，又让「只有 users.manage 的角色」在控制台里多一个 403
        //
        // 角色读的是**字典端点** /api/admin/roles/options（登录即可），
        //   不是管理端点 /api/admin/roles（需 roles.manage）。
        //   本页菜单声明的是 data-perm="users.manage"，菜单说「有 users.manage 就能用」，
        //   页面就不能暗地里还要 roles.manage：那样「只有 users.manage」的角色
        //   进得来这一页，却 403 降级成空角色表，表格「角色」列一片空白。
        const [usersRes, rolesRes] = await Promise.all([
            fetch('/api/admin/users', { credentials: 'include' }),
            fetch('/api/admin/roles/options', { credentials: 'include' })
        ]);

        if (usersRes.status === 401) { window.location.href = '/login'; return; }

        const users = await usersRes.json();
        const roles = rolesRes.ok ? await rolesRes.json() : [];
        _roleNameMap = {};
        roles.forEach(r => { _roleNameMap[r.id] = r.name; });
        _usersAll = users;
        renderUsersTable();
    } catch (e) {
        console.error('加载用户列表失败:', e);
        tbody.innerHTML = '<tr><td colspan="7" class="cell-empty error">加载失败</td></tr>';
    }
}

// 账号一次性全量返回，所以搜索走前端过滤，与「人员管理」页同一套写法：
// 不加接口、不占 /api/admin 的限速配额，输入即时生效。
function renderUsersTable() {
    const tbody = document.getElementById('userTableBody');
    if (!tbody) return;
    const k = ((document.getElementById('userSearchInput') || {}).value || '').trim().toLowerCase();
    const list = _usersAll.filter(u => !k
        || (u.username || '').toLowerCase().includes(k)
        || (u.name || '').toLowerCase().includes(k));

    const countEl = document.getElementById('userCount');
    if (countEl) {
        countEl.textContent = _usersAll.length
            ? '共 ' + _usersAll.length + ' 个账号' + (k ? '（匹配 ' + list.length + '）' : '')
            : '';
    }

    if (list.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="cell-empty">'
            + (_usersAll.length === 0 ? '暂无用户' : '无匹配结果') + '</td></tr>';
        return;
    }

    tbody.innerHTML = list.map(user => {
            const isAllAccess = user.allAccess === true || user.role === 'ADMIN' || user.isAdmin === true;
            const groupsHtml = isAllAccess
                ? '<span style="color:var(--gray-500);">全部</span>'
                : (user.groups && user.groups.length > 0
                    ? user.groups.map(g => `<span class="tag">${esc(g)}</span>`).join('')
                    : '<span class="tag">default</span>');

            // 分组清单是**数组**，走 escJsonAttr：原先只把 " 转成 &quot;，
            // 分组名里带一个单引号就能截断 onclick='...' 属性（存储型 XSS）。
            const groupsJson = escJsonAttr(user.groups || ['default']);

            // 数据权限（社区/科室）：空 = 不限
            const commHtml = (user.allowedCommunities && user.allowedCommunities.length > 0)
                ? user.allowedCommunities.map(cm => `<span class="tag">${esc(cm)}</span>`).join(' ')
                : '<span style="color:var(--gray-500);">不限</span>';
            const depHtml = (user.allowedDepartments && user.allowedDepartments.length > 0)
                ? '<span style="color:var(--gray-500);">' + user.allowedDepartments.length + '个科室</span>'
                : '<span style="color:var(--gray-500);">不限</span>';
            const scopeHtml = commHtml + '<div style="margin-top:3px;">' + depHtml + '</div>';

            const isAdminUser = user.isAdmin === true || user.role === 'ADMIN';
            // 大屏号：空 = 默认（跟随可查看社区）；ALL = 全部社区；否则为社区代码
            const screenNoLabel = !user.screenNo
                ? '默认'
                : (user.screenNo === 'ALL' ? '全部社区' : user.screenNo);
            const screenScopeText = user.screenScope || (isAdminUser ? '全部社区' : '-');
            const screenToggle = isAdminUser
                ? '<span class="tag" style="color:var(--primary);">默认有</span>'
                : `<button class="btn-sm" onclick='toggleScreenPermission(${escAttr(user.username)}, ${user.screenEnabled === true}, this)' title="开通/关闭数据大屏权限"
                        style="${user.screenEnabled === true ? '' : 'color:var(--primary);border-color:var(--primary);'}">
                        <i class="ri-slideshow-3-line"></i>
                        ${user.screenEnabled === true ? '已开通' : '未开通'}
                    </button>`;
            // 三行并两行：这一列原先比别的列高出一截，整张表的节奏被它带着走
            const screenHtml = `${screenToggle}
                <div style="margin-top:4px;font-size:var(--fs-xs);white-space:nowrap;">
                    <a href="javascript:void(0)" onclick='showScreenSetting(${escAttr(user.username)})'
                       title="修改大屏号：决定该账号在大屏上可见的社区范围"
                       style="color:var(--primary);">大屏号：<strong>${esc(screenNoLabel)}</strong></a>
                    <span style="color:var(--text-3);"> · 可见 ${esc(screenScopeText)}</span>
                </div>`;

            const actionsHtml = user.isAdmin === true
                ? '<span style="color:var(--gray-500);">超级管理员</span>'
                : `<button class="btn-sm" onclick='showEditScope(${escAttr(user.username)})' title="角色与数据权限">
                        <i class="ri-scope-line"></i> 范围
                    </button>
                    <button class="btn-sm" onclick='editUserGroups(${escAttr(user.username)}, ${groupsJson})' title="编辑权限">
                        <i class="ri-shield-user-line"></i> 权限
                    </button>
                    <button class="btn-sm" onclick='showResetPwd(${escAttr(user.username)})' title="重置密码">
                        <i class="ri-key-line"></i> 密码
                    </button>
                    <button class="btn-sm" onclick='deleteUser(${escAttr(user.username)}, this)' title="删除" style="color:var(--danger);">
                        <i class="ri-delete-bin-line"></i>
                    </button>`;

            return `<tr>
                <td><strong class="tnum">${esc(user.username)}</strong></td>
                <td>${esc(user.name || '-')}</td>
                <td><span class="badge ${user.role === 'ADMIN' ? 'badge-primary' : 'badge-default'}">${esc(_roleNameMap[user.roleId] || (user.role === 'ADMIN' ? '管理员' : '普通用户'))}</span></td>
                <td>${groupsHtml}</td>
                <td>${scopeHtml}</td>
                <td>${screenHtml}</td>
                <td>${actionsHtml}</td>
            </tr>`;
        }).join('');
}

// ==================== 大屏权限开关 ====================
async function toggleScreenPermission(username, currentEnabled, btn) {
    const target = !currentEnabled;
    const msg = target
        ? `确认为工号 ${username} 开通数据大屏权限？开通后该账号可访问大屏（可见社区由「大屏号」决定）。`
        : `确认关闭工号 ${username} 的数据大屏权限？关闭后该账号无法访问大屏，侧边栏入口也会隐藏。`;
    if (!confirm(msg)) return;
    await sensitiveFeedback(btn, async () => {
        try {
            const res = await apiFetch(`/api/admin/users/${encodeURIComponent(username)}/screen`, {
                method: 'PUT',
                body: JSON.stringify({ enabled: target })
            });
            if (res.ok) {
                showToast('success', target ? '大屏权限已开通' : '大屏权限已关闭');
                return true;
            }
            const err = await res.json().catch(() => ({}));
            showToast('error', err.error || '设置失败');
            return false;
        } catch (e) {
            console.error('设置大屏权限失败:', e);
            showToast('error', '设置大屏权限失败');
            return false;
        }
    }, loadUsersList);
}

// ==================== 大屏设置（开关 + 大屏号） ====================
async function showScreenSetting(username) {
    try {
        const [usersRes, optsRes] = await Promise.all([
            fetch('/api/admin/users', { credentials: 'include' }),
            fetch('/api/admin/users/screen-no-options', { credentials: 'include' })
        ]);
        if (usersRes.status === 401) { window.location.href = '/login'; return; }
        const users = usersRes.ok ? await usersRes.json() : [];
        const me = users.find(u => u.username === username);
        if (!me) { showToast('error', '用户不存在'); return; }

        const options = optsRes.ok ? await optsRes.json() : [];
        const isAdminUser = me.isAdmin === true || me.role === 'ADMIN';
        const curNo = me.screenNo || '';

        // 当前值若已不在社区字典中（社区被停用/删除），补一个选项避免静默丢失
        const known = options.map(o => o.code);
        const extra = (curNo && curNo !== 'ALL' && !known.includes(curNo))
            ? [{ code: curNo, name: curNo + '（字典中不存在）' }]
            : [];

        const optHtml = [
            `<option value="" ${curNo === '' ? 'selected' : ''}>默认（跟随该账号的可查看社区）</option>`,
            `<option value="ALL" ${curNo === 'ALL' ? 'selected' : ''}>全部社区（ALL）</option>`
        ].concat(extra.concat(options).map(o =>
            `<option value="${esc(o.code)}" ${curNo === o.code ? 'selected' : ''}>${esc(o.code)} - ${esc(o.name)}</option>`
        )).join('');

        const enabledBlock = isAdminUser
            ? '<div style="color:var(--gray-500);font-size:13px;">管理员账号天然拥有大屏访问权限，不可关闭。</div>'
            : `<label style="display:flex;align-items:center;gap:8px;cursor:pointer;">
                    <input type="checkbox" id="screenEnabledChk" ${me.screenEnabled === true ? 'checked' : ''}>
                    <span>允许该账号访问数据大屏（默认开启）</span>
               </label>`;

        const html = `
            <div style="display:flex;flex-direction:column;gap:16px;">
                <div class="form-group">
                    <label>大屏访问</label>
                    ${enabledBlock}
                </div>
                <div class="form-group">
                    <label>大屏号（决定该账号在大屏上可见的社区范围）</label>
                    <select id="screenNoSel" class="form-control">${optHtml}</select>
                    <p style="color:var(--gray-500);font-size:12px;margin-top:6px;line-height:1.7;">
                        · <strong>默认</strong>：跟随该账号的「可查看社区」；未配置社区的普通账号将看不到任何社区数据。<br>
                        · <strong>全部社区（ALL）</strong>：适用于集中监控 / 管理岗位，大屏可查看全部社区。<br>
                        · <strong>指定社区</strong>：大屏只显示该社区。<br>
                        · 大屏号只影响数据大屏，不影响报表中心的数据权限。
                    </p>
                </div>
                <p style="color:var(--gray-500);font-size:12px;">
                    当前可见范围：<strong>${esc(me.screenScope || '-')}</strong>。保存后对方刷新大屏页面即生效。
                </p>
            </div>
        `;

        showModal('大屏设置 - ' + username, html, async () => {
            const body = { screenNo: document.getElementById('screenNoSel').value };
            const chk = document.getElementById('screenEnabledChk');
            if (chk) body.enabled = chk.checked;
            const res = await apiFetch(`/api/admin/users/${encodeURIComponent(username)}/screen`, {
                method: 'PUT',
                body: JSON.stringify(body)
            });
            if (res.ok) {
                showToast('success', '大屏设置已保存');
                loadUsersList();
            } else {
                const data = await res.json().catch(() => ({}));
                showToast('error', data.error || '保存失败');
                return false;
            }
        });
    } catch (e) {
        console.error('加载大屏设置失败:', e);
        showToast('error', '加载大屏设置失败');
    }
}

// ==================== 分组管理 - 直接加载 ====================
async function loadGroupsList() {
    const tbody = document.getElementById('groupTableBody');
    if (!tbody) return;
    tbody.innerHTML = skeletonRow(5);

    try {
        const res = await fetch('/api/admin/groups', { credentials: 'include' });
        if (res.status === 401) { window.location.href = '/login'; return; }
        const groups = await res.json();

        if (groups.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="cell-empty">暂无分组</td></tr>';
            return;
        }

        // 加载报表分组配置以获取每个分组的报表数
        //
        // 注意：这里**不能**把失败当成「空配置」：那样每个分组都会显示「0 个报表」，
        //    管理员会读成「这个分组里没有报表」， 而真相是「没读到」。
        //    （误导性数据比不显示更糟：它会诱导人去"修"一个根本没坏的东西。）
        //    另外 fetch 对 4xx/5xx **不抛异常**，所以 `catch (_) {}` 连
        //    「403 带 JSON body」都接不住（会走成 assignments 为 undefined），
        //    必须显式看 `res.ok`。
        let assignments = null;   // null = 没读到（区别于「读到了，是空的」）
        try {
            const configRes = await fetch('/api/admin/groups/config', { credentials: 'include' });
            if (configRes.ok) {
                const config = await configRes.json();
                assignments = config.assignments || {};
            } else {
                console.warn('报表分组配置加载失败（HTTP ' + configRes.status + '），报表数将显示为「—」');
            }
        } catch (e) {
            console.warn('报表分组配置加载异常，报表数将显示为「—」:', e);
        }
        const configLoaded = assignments !== null;

        // 统计每个分组的报表数
        const reportCounts = {};
        Object.values(assignments || {}).forEach(gid => {
            reportCounts[gid] = (reportCounts[gid] || 0) + 1;
        });

        tbody.innerHTML = groups.map(group => {
            // 配置没读到 → 显示「—」而不是 0（0 会被误读成「这个分组没有报表」）
            const count = configLoaded ? (reportCounts[group.id] || 0) : null;
            return `<tr>
                <td><code>${esc(group.id)}</code></td>
                <td><strong>${esc(group.name)}</strong></td>
                <td>${group.sort || 1}</td>
                <td><span class="tag"${count === null ? ' title="报表分组配置未读到，数字未知"' : ''}>${count === null ? '—' : count}</span></td>
                <td>
                    <div style="display:flex;gap:4px;flex-wrap:wrap;">
                        <button class="btn-sm" onclick='viewGroupReports(${escAttr(group.id)}, ${escAttr(group.name)})' title="查看报表">
                            <i class="ri-file-list-line"></i>
                        </button>
                        <button class="btn-sm" onclick='editGroup(${escAttr(group.id)}, ${escAttr(group.name)}, ${group.sort || 1})' title="编辑">
                            <i class="ri-edit-line"></i>
                        </button>
                        <button class="btn-sm" onclick='deleteGroup(${escAttr(group.id)}, this)' title="删除" style="color:var(--danger);">
                            <i class="ri-delete-bin-line"></i>
                        </button>
                    </div>
                </td>
            </tr>`;
        }).join('');
    } catch (e) {
        console.error('加载分组列表失败:', e);
        tbody.innerHTML = '<tr><td colspan="5" class="cell-empty error">加载失败</td></tr>';
    }
}

// ==================== 系统日志 - 直接加载 ====================
async function loadLogsDirect() {
    const logContent = document.getElementById('logContent');
    const logStats = document.getElementById('logStats');
    if (!logContent) return;

    logContent.innerHTML = '<div class="cell-empty">加载中...</div>';

    try {
        const res = await fetch('/api/admin/logs?lines=500&category=all', { credentials: 'include' });
        if (res.status === 401) { window.location.href = '/login'; return; }
        const data = await res.json();

        // 渲染统计
        if (logStats && data.stats) {
            logStats.innerHTML = `
                <span class="stat-item"><i class="ri-file-text-line"></i> 总计: ${data.stats.total}</span>
                <span class="stat-item" style="color:var(--info);"><i class="ri-lock-line"></i> 审计: ${data.stats.audit}</span>
                <span class="stat-item" style="color:var(--danger);"><i class="ri-error-warning-line"></i> 错误: ${data.stats.error}</span>
                <span class="stat-item" style="color:var(--warning);"><i class="ri-alert-line"></i> 警告: ${data.stats.warn}</span>
                <span class="stat-item" style="color:var(--danger);"><i class="ri-shield-line"></i> 安全: ${data.stats.security}</span>
            `;
        }

        // 渲染日志内容
        if (data.lines && data.lines.length > 0) {
            window._logLines = data.lines; // 缓存用于过滤
            renderLogLines(data.lines);
        } else {
            logContent.innerHTML = '<div class="cell-empty">暂无日志</div>';
        }
    } catch (e) {
        console.error('加载日志失败:', e);
        logContent.innerHTML = '<div class="cell-empty error">加载失败</div>';
    }
}

function renderLogLines(lines) {
    const logContent = document.getElementById('logContent');
    if (!logContent) return;

    logContent.innerHTML = lines.map(line => {
        let color = 'var(--gray-300)';
        if (line.type === 'error') color = 'var(--danger)';
        else if (line.type === 'warn') color = 'var(--warning)';
        else if (line.type === 'audit') color = 'var(--info)';
        else if (line.type === 'security') color = '#ff6b6b';
        return `<div style="color:${color};border-bottom:1px solid var(--gray-800);padding:4px 0;">${esc(line.content)}</div>`;
    }).join('');
}

function switchLogTab(btn, category) {
    document.querySelectorAll('.log-tab').forEach(t => t.classList.remove('active'));
    btn.classList.add('active');

    if (!window._logLines) return;

    const filtered = category === 'all'
        ? window._logLines
        : window._logLines.filter(l => l.type === category);

    renderLogLines(filtered);
}

function filterLogLines() {
    const keyword = document.getElementById('logSearchInput')?.value?.toLowerCase() || '';
    if (!window._logLines) return;

    const filtered = window._logLines.filter(l =>
        !keyword || l.content.toLowerCase().includes(keyword)
    );
    renderLogLines(filtered);
}

function refreshLogs() {
    loadLogsDirect();
}

function exportLogs(format) {
    window.open(`/api/admin/logs/export?category=all&format=${format}&lines=2000`, '_blank');
}

// ==================== 用户分组权限编辑 ====================
async function editUserGroups(username, currentGroups) {
    try {
        // 用字典端点：本弹窗只需「有哪些分组」，不需要 groups.manage。
        // 原先调 /api/admin/groups（需 groups.manage），只有 users.manage 的角色会 403，
        // 而本弹窗保存时按勾选项**整体覆盖**，勾选框全空 → 一保存就把该工号的报表分组静默清空
        const res = await fetch('/api/admin/groups/options', { credentials: 'include' });
        if (!res.ok) throw new Error('加载分组失败（HTTP ' + res.status + '）');
        const groups = await res.json();
        if (!Array.isArray(groups)) throw new Error('分组数据格式异常');

        const checkboxes = groups.map(g => `
            <label style="display:flex;align-items:center;gap:8px;padding:8px;cursor:pointer;">
                <input type="checkbox" name="userGroups" value="${esc(g.id)}"
                    ${currentGroups.includes(g.id) ? 'checked' : ''}>
                <span>${esc(g.name)} (${esc(g.id)})</span>
            </label>
        `).join('');

        const html = `
            <div style="margin-bottom:16px;">
                <strong>用户: ${esc(username)}</strong>
                <p style="color:var(--gray-500);font-size:13px;margin-top:4px;">勾选该用户可以查看的报表分组。不勾选则仅可访问 default 分组（不是全部）</p>
            </div>
            <div style="max-height:300px;overflow-y:auto;border:1px solid var(--gray-200);border-radius:var(--radius);padding:8px;">
                ${checkboxes || '<p style="color:var(--gray-500);text-align:center;padding:20px;">暂无分组</p>'}
            </div>
        `;

        showModal('编辑用户权限', html, async () => {
            const checked = Array.from(document.querySelectorAll('input[name="userGroups"]:checked')).map(el => el.value);
            const res = await fetch(`/api/admin/users/${encodeURIComponent(username)}/groups`, {
                method: 'PUT',
                credentials: 'include',
                headers: csrfHeaders(),
                body: JSON.stringify({ groups: checked })
            });
            if (res.ok) {
                showToast('success', checked.length ? '权限更新成功' : '已设为仅 default 分组');
                loadUsersList();
            } else {
                const data = await res.json().catch(() => ({}));
                showToast('error', data.error || '更新失败');
                return false;
            }
        });
    } catch (e) {
        showToast('error', '加载分组失败');
    }
}

// ==================== 重置密码弹窗 ====================
function showResetPwd(username) {
    state.resetPwdTarget = username;
    const html = `
        <div style="margin-bottom:16px;">
            <strong>重置用户密码: ${esc(username)}</strong>
            <p style="color:var(--gray-500);font-size:12px;margin-top:8px;">可指定新密码（至少8位，含字母和数字），或留空重置为初始密码 666999。两种方式用户下次登录都会被要求先修改密码。</p>
        </div>
        <div class="form-group">
            <label>新密码（可选）</label>
            <input type="password" id="resetPwdNew" placeholder="留空则重置为初始密码 666999" class="form-control">
        </div>
    `;

    showModal('重置密码', html, async () => {
        const newPassword = (document.getElementById('resetPwdNew').value || '').trim();
        // 指定密码：走 password 接口；留空：走随机临时密码接口
        if (newPassword) {
            if (newPassword.length < 8 || !/[A-Za-z]/.test(newPassword) || !/[0-9]/.test(newPassword)) {
                showToast('error', '密码至少8位，且必须同时包含字母和数字');
                return false;
            }
            const res = await fetch(`/api/admin/users/${encodeURIComponent(username)}/password`, {
                method: 'PUT',
                credentials: 'include',
                headers: csrfHeaders(),
                body: JSON.stringify({ password: newPassword })
            });
            if (res.ok) {
                showToast('success', '密码已重置，该用户已登录的会话同时失效，下次登录须先改密码');
            } else {
                const data = await res.json().catch(() => ({}));
                showToast('error', data.error || '重置失败');
                return false;
            }
        } else {
            const res = await fetch(`/api/admin/users/${encodeURIComponent(username)}/reset-password`, {
                method: 'PUT',
                credentials: 'include',
                headers: csrfHeaders()
            });
            const data = await res.json().catch(() => ({}));
            if (res.ok) {
                const temp = data.tempPassword || '';
                if (temp) {
                    alert('初始密码（请当面告知用户，并要求其首次登录后立即修改）：\n\n' + temp
                        + '\n\n注意：这是全系统共用的初始密码，不是随机生成的强口令。');
                }
                showToast('success', '已重置为初始密码，该用户已登录的会话同时失效');
            } else {
                showToast('error', data.error || '重置失败');
                return false;
            }
        }
    });
}

// ==================== 编辑分组弹窗 ====================
function editGroup(groupId, currentName, currentSort) {
    const html = `
        <div style="display:flex;flex-direction:column;gap:16px;">
            <div class="form-group">
                <label>分组ID</label>
                <input type="text" value="${esc(groupId)}" disabled class="form-control" style="background:var(--gray-100);">
            </div>
            <div class="form-group">
                <label>分组名称</label>
                <input type="text" id="editGroupName" value="${esc(currentName)}" class="form-control">
            </div>
            <div class="form-group">
                <label>排序</label>
                <input type="number" id="editGroupSort" value="${esc(currentSort)}" min="1" class="form-control">
            </div>
        </div>
    `;

    showModal('编辑分组', html, async () => {
        const name = document.getElementById('editGroupName').value;
        const sort = parseInt(document.getElementById('editGroupSort').value) || 1;

        const res = await fetch(`/api/admin/groups/${encodeURIComponent(groupId)}`, {
            method: 'PUT',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({ name, sort })
        });
        if (res.ok) {
            showToast('success', '分组更新成功');
            loadGroupsList();
        } else {
            const data = await res.json();
            showToast('error', data.error || '更新失败');
        }
    });
}

// ==================== 角色下拉（三处共用的唯一实现） ====================
/**
 * 渲染角色下拉选项。
 *
 * 必须展示**全部**角色。早先「新增用户」「范围」两处各自过滤掉了 role_screen，
 *   而「角色管理」页显示全部，造成「角色管理里有、这里没有」的不一致。
 *   `role_screen`（大屏查看）是**合法的可分配角色**（该工号登录后直达大屏），
 *   只是语义与常规角色不同，故用 optgroup 单独标注，而不是让它凭空消失。
 */
function roleOptionsHtml(roles, selectedId) {
    const list = roles || [];
    const normal = list.filter(r => r.id !== 'role_screen');
    const screen = list.filter(r => r.id === 'role_screen');
    const opt = (r) => `<option value="${esc(r.id)}" ${r.id === selectedId ? 'selected' : ''}>${esc(r.name)}</option>`;
    let html = normal.map(opt).join('');
    if (screen.length > 0) {
        html += '<optgroup label="大屏通道（该工号登录后直达大屏）">' + screen.map(opt).join('') + '</optgroup>';
    }
    return html;
}

// ==================== 新增用户弹窗 ====================
async function showAddUserModal() {
    try {
        const [groupsRes, rolesRes, commsRes, depsRes] = await Promise.all([
            fetch('/api/admin/groups/options', { credentials: 'include' }),
            // 角色是「字典」：用 /roles/options（登录即可），不要用 /roles（需 roles.manage）。
            // 否则「只有 users.manage」的角色打不开角色下拉，而这个弹窗**必须**选角色，
            // 下拉全空就等于建不了用户，且没有任何提示。
            fetch('/api/admin/roles/options', { credentials: 'include' }),
            fetch('/api/admin/communities/enabled', { credentials: 'include' }),
            fetch('/api/admin/departments/enabled', { credentials: 'include' })
        ]);
        // 分组是「字典」：用 /groups/options（登录即可），不要用 /groups（需 groups.manage）。
        // 且**不允许静默降级**，空的分组清单会让人以为「本来就没有分组」，
        // 从而在不知情的情况下不分配任何报表分组。加载不到就明确报错。
        if (!groupsRes.ok) throw new Error('加载报表分组失败（HTTP ' + groupsRes.status + '）');
        const groups = await groupsRes.json();
        if (!Array.isArray(groups)) throw new Error('报表分组数据格式异常');
        const roles = rolesRes.ok ? await rolesRes.json() : [];
        const communities = commsRes.ok ? await commsRes.json() : [];
        // /enabled 已只返回启用项，无需再本地过滤
        const departments = depsRes.ok ? await depsRes.json() : [];

        const groupBoxes = groups.map(g => `
            <label style="display:flex;align-items:center;gap:8px;padding:8px;cursor:pointer;">
                <input type="checkbox" name="newUserGroups" value="${esc(g.id)}">
                <span>${esc(g.name)} (${esc(g.id)})</span>
            </label>`).join('');
        const commBoxes = communities.map(cm => `
            <label style="display:flex;align-items:center;gap:8px;padding:6px 8px;cursor:pointer;">
                <input type="checkbox" name="newUserCommunities" value="${esc(cm.code)}">
                <span>${esc(cm.name)}</span>
            </label>`).join('');
        const depBoxes = departments.map(d => `
            <label style="display:flex;align-items:center;gap:8px;padding:6px 8px;cursor:pointer;">
                <input type="checkbox" name="newUserDepartments" value="${esc(d.code)}">
                <span>${esc(d.name)}${d.community ? '（' + esc(d.community) + '）' : ''}</span>
            </label>`).join('');
        const roleOptions = roleOptionsHtml(roles);

        const html = `
            <div style="display:flex;flex-direction:column;gap:16px;">
                <div class="form-group">
                    <label>工号（默认自动续写，可改为其他号）</label>
                    <input type="text" id="newUserUsername" class="form-control" placeholder="如 0016 或 6666">
                    <p style="color:var(--gray-500);font-size:12px;margin-top:4px;">3-20位字母/数字/下划线；初始密码为 666999，首次登录强制修改</p>
                </div>
                <div class="form-group">
                    <label>姓名</label>
                    <input type="text" id="newUserName" placeholder="输入真实姓名" class="form-control">
                </div>
                <div class="form-group">
                    <label>角色（决定可点开的菜单）</label>
                    <select id="newUserRole" class="form-control">${roleOptions}</select>
                </div>
                <div class="form-group">
                    <label>数据权限：可查看社区（不勾选 = 不限）</label>
                    <div style="max-height:120px;overflow-y:auto;border:1px solid var(--gray-200);border-radius:var(--radius);padding:8px;">
                        ${commBoxes || '<p style="color:var(--gray-500);text-align:center;">社区字典为空</p>'}
                    </div>
                </div>
                <div class="form-group">
                    <label>数据权限：可查看科室（不勾选 = 不限，数据过滤为二期）</label>
                    <div style="max-height:150px;overflow-y:auto;border:1px solid var(--gray-200);border-radius:var(--radius);padding:8px;">
                        ${depBoxes || '<p style="color:var(--gray-500);text-align:center;">科室字典为空，可在科室管理从HIS导入</p>'}
                    </div>
                </div>
                <div class="form-group">
                    <label>可查看的报表分组</label>
                    <p style="color:var(--gray-500);font-size:12px;margin-bottom:8px;">不勾选则仅可查看默认分组（default），不会获得全部报表权限</p>
                    <div style="max-height:200px;overflow-y:auto;border:1px solid var(--gray-200);border-radius:var(--radius);padding:8px;">
                        ${groupBoxes || '<p style="color:var(--gray-500);text-align:center;">暂无分组</p>'}
                    </div>
                </div>
            </div>
        `;

        // 工号默认值：后端 next-id 优先，失败用前端计算兜底
        (async () => {
            var nextId = '';
            try {
                const r = await fetch('/api/admin/users/next-id', { credentials: 'include' });
                if (r.ok) { const d = await r.json(); nextId = d.nextId || ''; }
            } catch (e) {}
            if (!nextId) {
                try {
                    const usersRes = await fetch('/api/admin/users', { credentials: 'include' });
                    if (usersRes.ok) nextId = getNextUserId(await usersRes.json());
                } catch (e) {}
            }
            const el = document.getElementById('newUserUsername');
            if (el && !el.value) el.value = nextId;
        })();

        showModal('新增用户', html, async () => {
            const username = document.getElementById('newUserUsername').value.trim();
            const name = document.getElementById('newUserName').value.trim();
            const roleId = document.getElementById('newUserRole').value;
            if (!username || !/^[A-Za-z0-9_]{3,20}$/.test(username)) { showToast('error', '工号无效：3-20位字母/数字/下划线'); return false; }
            if (!name) { showToast('error', '请填写姓名'); return false; }
            const groups = Array.from(document.querySelectorAll('input[name="newUserGroups"]:checked')).map(el => el.value);
            const communities = Array.from(document.querySelectorAll('input[name="newUserCommunities"]:checked')).map(el => el.value);
            const departments = Array.from(document.querySelectorAll('input[name="newUserDepartments"]:checked')).map(el => el.value);

            const res = await fetch('/api/admin/users', {
                method: 'POST',
                credentials: 'include',
                headers: csrfHeaders(),
                body: JSON.stringify({ username, name, roleId, groups, allowedCommunities: communities, allowedDepartments: departments })
            });

            if (res.ok) {
                // 注意：这里曾有一次**多余的** `PUT /api/admin/users/{工号}/groups`：
                //    上面的 POST 已经把 `groups` 一起提交了，后端在 createUser 之后
                //    紧接着 setUserGroups（见 UserController#createUser）。
                //    那次 PUT 既重复（多打一次 `/api/admin/*`，而该前缀限流 5 次/秒），
                //    又把失败吞进 `catch(e) {}`，而且 fetch 对 4xx/5xx **不抛异常**，
                //    所以那个 catch 连"兜底"都算不上，是纯粹的静默失败写法。
                //    删掉它：行为不变，少一次请求、少一个坑。
                alert('用户创建成功。初始密码 666999（首次登录强制修改，请告知用户）。');
                showToast('success', '用户创建成功');
                loadUsersList();
            } else {
                const data = await res.json().catch(() => ({}));
                showToast('error', data.error || '创建失败');
                return false;
            }
        });
    } catch (e) {
        showToast('error', '加载基础数据失败');
    }
}

// ==================== 新增分组弹窗 ====================
function showAddGroupModal() {
    const html = `
        <div style="display:flex;flex-direction:column;gap:16px;">
            <div class="form-group">
                <label>分组ID</label>
                <input type="text" id="newGroupId" placeholder="字母、数字、下划线" class="form-control">
            </div>
            <div class="form-group">
                <label>分组名称</label>
                <input type="text" id="newGroupName" placeholder="显示名称" class="form-control">
            </div>
            <div class="form-group">
                <label>排序</label>
                <input type="number" id="newGroupSort" value="1" min="1" class="form-control">
            </div>
        </div>
    `;

    showModal('新增分组', html, async () => {
        const id = document.getElementById('newGroupId').value;
        const name = document.getElementById('newGroupName').value;
        const sort = parseInt(document.getElementById('newGroupSort').value) || 1;

        const res = await fetch('/api/admin/groups', {
            method: 'POST',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({ id, name, sort })
        });

        if (res.ok) {
            showToast('success', '分组创建成功');
            loadGroupsList();
        } else {
            const data = await res.json();
            showToast('error', data.error || '创建失败');
        }
    });
}

// ==================== 通用弹窗 ====================
/**
 * 弹窗打开后把焦点送进去（配合第 218 行既有的 Esc 处理器，键盘用户能开也能关）。
 *
 * <p>这里刻意不再实现一套关闭逻辑：全站 Esc 关闭只有一处（去掉 .modal.active），
 * 两处各关一半是「必须一致却各写一遍」的典型形态。
 */
function focusModal(modal) {
    if (!modal) return;
    modal.setAttribute('role', 'dialog');
    modal.setAttribute('aria-modal', 'true');
    // 先找正文里的输入框：标题栏的 × 在 DOM 里排在前面，直接取「第一个可聚焦元素」
    // 会让用户一进来焦点就在关闭按钮上，回车等于关窗。
    const first = modal.querySelector('.modal-body input:not([type=hidden]), .modal-body select, .modal-body textarea')
        || modal.querySelector('input:not([type=hidden]), select, textarea, button');
    if (first) requestAnimationFrame(() => { try { first.focus(); } catch (e) { /* 不可聚焦就算了 */ } });
}

function showModal(title, content, onConfirm) {
    // 移除旧弹窗
    const oldModal = document.getElementById('commonModal');
    if (oldModal) oldModal.remove();

    const modal = document.createElement('div');
    modal.id = 'commonModal';
    modal.className = 'modal';
    modal.innerHTML = `
        <div class="modal-overlay" onclick="this.parentElement.remove()"></div>
        <div class="modal-container" style="width:500px;">
            <div class="modal-header">
                <h3>${esc(title)}</h3>
                <button class="modal-close" onclick="this.closest('.modal').remove()"><i class="ri-close-line"></i></button>
            </div>
            <div class="modal-body">${content}</div>
            <div class="modal-footer">
                <button class="btn-sm" onclick="this.closest('.modal').remove()" style="padding:10px 20px;">取消</button>
                <button class="btn-primary" id="modalConfirmBtn">确认</button>
            </div>
        </div>
    `;

    document.body.appendChild(modal);
    // 关键修复：添加active类使弹窗可见
    requestAnimationFrame(() => modal.classList.add('active'));
    focusModal(modal);

    document.getElementById('modalConfirmBtn').addEventListener('click', async () => {
        try {
            await onConfirm();
            modal.remove();
        } catch (e) {
            showToast('error', e.message || '操作失败');
        }
    });
}

// ==================== 数据源管理 ====================
async function loadDataSources() {
    const container = document.getElementById('datasourceList');
    if (container) container.innerHTML = '<div class="empty-state"><div class="loading-spinner"></div><p style="margin-top:16px;">加载中...</p></div>';
    try {
        // 默认数据源 ID 只在本页用于给那一行打「默认」标记，在这里按需取，
        // 而不是放在全局初始化里让所有人（含无 datasource.manage 的角色）白吃 403（见 D8）。
        await loadDefaultDataSourceId();
        const res = await apiFetch('/api/admin/datasources');
        if (res.ok) {
            const dataSources = await res.json();
            renderDataSourceList(dataSources);
        } else {
            // 非 2xx（403/500/HTML 错误页）原先什么都不做 → 转圈永远停在「加载中...」
            if (container) container.innerHTML = '<div class="empty-state"><i class="ri-error-warning-line"></i><h3>加载失败</h3><p>请刷新重试</p></div>';
            showToast('error', '数据源加载失败（HTTP ' + res.status + '）');
        }
    } catch (e) {
        console.error('加载数据源失败:', e);
        if (container) container.innerHTML = '<div class="empty-state"><i class="ri-error-warning-line"></i><h3>加载失败</h3><p>请刷新重试</p></div>';
        showToast('error', '数据源加载失败');
    }
}

function renderDataSourceList(dataSources) {
    const container = document.getElementById('datasourceList');
    if (!container) return;

    if (dataSources.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <i class="ri-database-2-line"></i>
                <h3>暂无数据源</h3>
                <p>点击上方"新建数据源"按钮创建第一个数据源</p>
            </div>`;
        return;
    }

    const typeConfig = {
        oracle: { letter: 'O', color: 'oracle', label: 'Oracle' },
        mysql: { letter: 'M', color: 'mysql', label: 'MySQL' },
        postgresql: { letter: 'P', color: 'postgresql', label: 'PostgreSQL' },
        sqlserver: { letter: 'S', color: 'sqlserver', label: 'SQL Server' }
    };

    container.innerHTML = dataSources.map(ds => {
        const config = typeConfig[ds.type] || typeConfig.oracle;
        const isDefault = ds.id === state.defaultDataSourceId;
        const urlInfo = parseDataSourceUrl(ds.url, ds.type);

        return `
            <div class="datasource-card ${isDefault ? 'default' : ''}">
                <div class="ds-icon ${config.color}">${config.letter}</div>
                <div class="ds-info">
                    <h4>
                        ${esc(ds.name)}
                        ${isDefault ? '<span class="tag green" style="margin-left: 8px;">默认</span>' : ''}
                    </h4>
                    <p>${config.label} · ${esc(urlInfo.host)}:${esc(urlInfo.port)}</p>
                    <span class="ds-status connected"><i class="ri-checkbox-circle-line"></i> 已配置</span>
                </div>
                <div class="ds-actions">
                    <button class="btn-sm" onclick="testDataSource(${escAttr(ds.id)})">
                        <i class="ri-link"></i> 测试连接
                    </button>
                    <button class="btn-sm" onclick="editDataSource(${escAttr(ds.id)})">
                        <i class="ri-edit-line"></i> 编辑
                    </button>
                    ${!isDefault ? `
                        <button class="btn-sm" onclick="setDefaultDataSource(${escAttr(ds.id)})">
                            <i class="ri-star-line"></i> 设为默认
                        </button>
                        <button class="btn-sm danger" onclick="deleteDataSource(${escAttr(ds.id)}, this)">
                            <i class="ri-delete-bin-line"></i> 删除
                        </button>` : ''}
                </div>
            </div>`;
    }).join('');
}

function parseDataSourceUrl(url, type) {
    if (!url) return { host: 'localhost', port: '1521', database: '' };

    // 处理环境变量格式
    if (url.includes('${')) {
        const match = url.match(/\$\{[^}]*:([^}]+)\}/);
        if (match) url = match[1];
    }

    let host = 'localhost';
    let port = '';
    let database = '';

    try {
        if (type === 'oracle') {
            const match = url.match(/@([^:]+):(\d+):(.+)/);
            if (match) { host = match[1]; port = match[2]; database = match[3]; }
        } else if (type === 'mysql' || type === 'postgresql') {
            const match = url.match(/\/\/([^:]+):(\d+)\/(.+)/);
            if (match) { host = match[1]; port = match[2]; database = match[3]; }
        } else if (type === 'sqlserver') {
            const match = url.match(/\/\/([^:]+):(\d+);databaseName=(.+)/);
            if (match) { host = match[1]; port = match[2]; database = match[3]; }
        }
    } catch (e) {
        // 解析失败使用默认值
    }

    return { host, port, database };
}

function showAddDataSource() {
    document.getElementById('dsModalTitle').textContent = '新建数据源';
    document.getElementById('dsEditId').value = '';
    document.getElementById('dsName').value = '';
    document.getElementById('dsType').value = 'oracle';
    document.getElementById('dsHost').value = 'localhost';
    document.getElementById('dsPort').value = '1521';
    document.getElementById('dsDatabase').value = '';
    document.getElementById('dsUsername').value = '';
    document.getElementById('dsPassword').value = '';
    document.getElementById('dsDescription').value = '';
    document.getElementById('dsUrl').value = '';
    document.getElementById('dsTestResult').innerHTML = '';
    updateDsFields();
    document.getElementById('datasourceModal').classList.add('active');
}

async function editDataSource(id) {
    try {
        const res = await fetch(`/api/admin/datasources/${encodeURIComponent(id)}`);
        if (res.ok) {
            const ds = await res.json();
            document.getElementById('dsModalTitle').textContent = '编辑数据源';
            document.getElementById('dsEditId').value = ds.id;
            document.getElementById('dsName').value = ds.name || '';
            document.getElementById('dsType').value = ds.type || 'oracle';
            document.getElementById('dsUsername').value = ds.username || '';
            document.getElementById('dsPassword').value = '';
            document.getElementById('dsDescription').value = ds.description || '';
            document.getElementById('dsUrl').value = ds.url || '';
            document.getElementById('dsTestResult').innerHTML = '';

            const urlInfo = parseDataSourceUrl(ds.url, ds.type);
            document.getElementById('dsHost').value = urlInfo.host || 'localhost';
            document.getElementById('dsPort').value = urlInfo.port || '';
            document.getElementById('dsDatabase').value = urlInfo.database || '';

            updateDsFields();
            document.getElementById('datasourceModal').classList.add('active');
        }
    } catch (e) {
        showToast('error', '加载数据源失败');
    }
}

function hideDataSourceModal() {
    document.getElementById('datasourceModal').classList.remove('active');
}

function updateDsFields() {
    const type = document.getElementById('dsType').value;
    const defaults = {
        oracle: { port: '1521', database: 'orcl' },
        mysql: { port: '3306', database: 'his_db' },
        postgresql: { port: '5432', database: 'his_db' },
        sqlserver: { port: '1433', database: 'his_db' }
    };

    const config = defaults[type] || defaults.oracle;
    const currentPort = document.getElementById('dsPort').value;
    if (!currentPort || Object.values(defaults).some(d => d.port === currentPort)) {
        document.getElementById('dsPort').value = config.port;
    }

    updateDsUrl();
}

function updateDsUrl() {
    const type = document.getElementById('dsType').value;
    const host = document.getElementById('dsHost').value || 'localhost';
    const port = document.getElementById('dsPort').value || '1521';
    const database = document.getElementById('dsDatabase').value || '';

    let url = '';
    switch (type) {
        case 'oracle': url = `jdbc:oracle:thin:@${host}:${port}:${database}`; break;
        case 'mysql': url = `jdbc:mysql://${host}:${port}/${database}`; break;
        case 'postgresql': url = `jdbc:postgresql://${host}:${port}/${database}`; break;
        case 'sqlserver': url = `jdbc:sqlserver://${host}:${port};databaseName=${database}`; break;
    }

    document.getElementById('dsUrl').value = url;
}

async function saveDataSource() {
    const editId = document.getElementById('dsEditId').value;
    const name = document.getElementById('dsName').value;
    const type = document.getElementById('dsType').value;
    const host = document.getElementById('dsHost').value;
    const port = document.getElementById('dsPort').value;
    const database = document.getElementById('dsDatabase').value;
    const username = document.getElementById('dsUsername').value;
    const password = document.getElementById('dsPassword').value;
    const description = document.getElementById('dsDescription').value;
    const url = document.getElementById('dsUrl').value;

    if (!name || !host || !port || !database || !username) {
        showToast('error', '请填写所有必填字段');
        return;
    }

    const finalUrl = url || generateUrl(type, host, port, database);

    const ds = { name, type, url: finalUrl, username, description, active: true, driverClassName: DS_DRIVERS[type] || DS_DRIVERS.oracle };
    if (password) ds.password = password;

    try {
        const method = editId ? 'PUT' : 'POST';
        const urlPath = editId ? `/api/admin/datasources/${encodeURIComponent(editId)}` : '/api/admin/datasources';

        const res = await fetch(urlPath, {
            method: method,
            headers: csrfHeaders(),
            body: JSON.stringify(ds)
        });

        if (res.ok) {
            showToast('success', editId ? '数据源更新成功' : '数据源创建成功');
            hideDataSourceModal();
            loadDataSources();
        } else {
            const data = await res.json();
            showToast('error', data.error || '保存失败');
        }
    } catch (e) {
        showToast('error', '保存失败: ' + e.message);
    }
}

// 各数据库类型的默认 JDBC 驱动类名。新建与编辑数据源共用这一份 ，
// 原先两处各写一遍字面量，将来加一种类型很容易只改到一边。
var DS_DRIVERS = {
    oracle: 'oracle.jdbc.OracleDriver',
    mysql: 'com.mysql.cj.jdbc.Driver',
    postgresql: 'org.postgresql.Driver',
    sqlserver: 'com.microsoft.sqlserver.jdbc.SQLServerDriver'
};

function generateUrl(type, host, port, database) {
    switch (type) {
        case 'oracle': return `jdbc:oracle:thin:@${host}:${port}:${database}`;
        case 'mysql': return `jdbc:mysql://${host}:${port}/${database}`;
        case 'postgresql': return `jdbc:postgresql://${host}:${port}/${database}`;
        case 'sqlserver': return `jdbc:sqlserver://${host}:${port};databaseName=${database}`;
        default: return '';
    }
}

async function testDataSource(id) {
    try {
        const res = await fetch(`/api/admin/datasources/${encodeURIComponent(id)}/test`, {
            method: 'POST',
            headers: csrfHeaders()
        });

        const result = await res.json();
        if (result.success) {
            showToast('success', `连接成功 (${result.elapsed}ms)`);
        } else {
            showToast('error', result.message || '连接失败');
        }
    } catch (e) {
        showToast('error', '测试失败: ' + e.message);
    }
}

async function testNewDataSource() {
    const type = document.getElementById('dsType').value;
    const url = document.getElementById('dsUrl').value;
    const username = document.getElementById('dsUsername').value;
    const password = document.getElementById('dsPassword').value;

    if (!url || !username) {
        showToast('error', '请填写连接URL和用户名');
        return;
    }

    const ds = {
        type: type,
        url: url,
        username: username,
        name: document.getElementById("dsName").value.trim(),
        driverClassName: DS_DRIVERS[type] || DS_DRIVERS.oracle
    };

    const resultDiv = document.getElementById('dsTestResult');
    resultDiv.innerHTML = '<div style="color: var(--info);"><i class="ri-loader-line"></i> 测试中...</div>';

    try {
        const res = await fetch('/api/admin/datasources/test', {
            method: 'POST',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify(ds)
        });

        const result = await res.json();
        if (result.success) {
            resultDiv.innerHTML = `
                <div style="color: var(--success); padding: 12px; background: var(--gray-50); border-radius: var(--radius);">
                    <i class="ri-checkbox-circle-line"></i> 连接成功<br>
                    <small>耗时: ${esc(result.elapsed)}ms | 驱动: ${esc(result.driver || '-')} | 版本: ${esc(result.version || '-')}</small>
                </div>`;
        } else {
            resultDiv.innerHTML = `
                <div style="color: var(--danger); padding: 12px; background: #fef2f2; border-radius: var(--radius);">
                    <i class="ri-error-warning-line"></i> ${esc(result.message || '连接失败')}
                </div>`;
        }
    } catch (e) {
        resultDiv.innerHTML = `
            <div style="color: var(--danger); padding: 12px; background: #fef2f2; border-radius: var(--radius);">
                <i class="ri-error-warning-line"></i> 测试失败: ${esc(e.message)}
            </div>`;
    }
}

async function deleteDataSource(id, btn) {
    if (!confirm('注意：确定要删除此数据源吗？\n\n删除后使用该数据源的报表将无法查询！')) return;
    await sensitiveFeedback(btn, async () => {
        try {
            const res = await fetch(`/api/admin/datasources/${encodeURIComponent(id)}`, {
                method: 'DELETE',
                headers: csrfHeaders()
            });
            if (res.ok) {
                showToast('success', '数据源已删除');
                return true;
            }
            const data = await res.json();
            showToast('error', data.error || '删除失败');
            return false;
        } catch (e) {
            showToast('error', '删除失败: ' + e.message);
            return false;
        }
    }, loadDataSources);
}

async function setDefaultDataSource(id) {
    try {
        const res = await fetch(`/api/admin/datasources/${encodeURIComponent(id)}/set-default`, {
            method: 'POST',
            headers: csrfHeaders()
        });

        if (res.ok) {
            showToast('success', '已设置为默认数据源');
            state.defaultDataSourceId = id;
            loadDataSources();
        } else {
            const data = await res.json();
            showToast('error', data.error || '设置失败');
        }
    } catch (e) {
        showToast('error', '设置失败: ' + e.message);
    }
}

async function loadDefaultDataSourceId() {
    try {
        const res = await fetch('/api/admin/datasources/default-id');
        if (res.ok) {
            const data = await res.json();
            state.defaultDataSourceId = data.id;
        }
    } catch (e) {
        console.error('加载默认数据源ID失败:', e);
    }
}

// ==================== 初始化 ====================
document.addEventListener('DOMContentLoaded', function() {
    initNavigation();
    initSidebar();
    initNavGroups();
    initTableDragScroll(); // 初始化表格拖拽滚动

    // 落地页导航**必须等权限加载完成**才能决定。
    //
    //   缺陷（由探针脚本 render-check-landing.mjs 抓出来的）：
    //   `loadUserInfo()` 是 async 函数，之前**没有被 await**，而决定落地页的代码
    //   写在它后面的同步流程里，于是那几行跑的时候 `state.landingPage` 还没被赋值，
    //   永远是 undefined，`var landing = state.landingPage || null` 恒为 null。
    //   表现：**任何角色**打开页面都停在写死的「工作台」上。
    //     · 有权限的角色 → 碰巧看着没错（工作台本来就该是它的落点）
    //     · 零权限角色   → 落在「菜单无一项高亮、内容被权限隐藏」的空工作台上，
    //                      正是本功能要修的那个场景，却**完全没生效**
    //   所以这不是「少个兜底」，而是「整个新功能是死的」。探针就是为此而写。
    //
    //   修法：把导航收进 loadUserInfo() 的 then 里（见 navigateToLandingPage），
    //   保证「先拿到权限点 → 再决定去哪」。loadUserInfo 内部已有 try/catch，
    //   这里再挂一个 catch 兜底到工作台，避免接口挂了页面卡在白屏。
    loadUserInfo()
        .then(function () { navigateToLandingPage(); })
        .catch(function (e) {
            console.error('加载用户信息失败，回退到工作台:', e);
            navigateTo('dashboard');
        });

    // 注意：不要在这里调 loadDefaultDataSourceId()（D8）：
    //    它读的 /api/admin/datasources/default-id 在类级 @PreAuthorize('datasource.manage') 下，
    //    而这里是**所有用户**每次打开页面都会跑，非管理员会静默吃一个 403（被 res.ok 吞掉），
    //    且该值只在「数据源管理」页用到。改为在 loadDataSources() 里按需加载。

    // 监听数据源表单变化
    ['dsHost', 'dsPort', 'dsDatabase'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.addEventListener('input', updateDsUrl);
    });

    // 搜索框实时过滤报表（防抖300ms）
    const searchInput = document.getElementById('searchInput');
    if (searchInput) {
        searchInput.addEventListener('input', debounce(function() {
            const keyword = this.value.trim().toLowerCase();
            const cards = document.querySelectorAll('#reportGrid .report-card');
            cards.forEach(card => {
                const text = card.textContent.toLowerCase();
                card.style.display = (!keyword || text.includes(keyword)) ? '' : 'none';
            });
        }, 300));
        // Enter键跳转到报表中心并聚焦搜索
        searchInput.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                if (state.currentPage !== 'reports') navigateTo('reports');
            }
        });
    }

    renderRecentReports();

    console.log('HIS报表系统初始化完成');
});

/**
 * 默认落到「当前角色真的进得去」的那个页面。
 *
 * <p>**必须在 {@link loadUserInfo} 完成后调用**，它要读两样东西：
 * <ul>
 *   <li>{@code state.landingPage}（由 loadUserInfo 里的 {@link chooseLandingPage} 算出）</li>
 *   <li>菜单的实际可见性（由 loadUserInfo 里对 {@code [data-perm]} 的统一显隐决定）</li>
 * </ul>
 *
 * <p>不能写成 {@code state.landingPage ?: 'dashboard'}：
 * 零权限角色既没有 landingPage，也不能去 dashboard（它已被权限隐藏），
 * 那种情况必须显示「无权限」落地页（见 {@link chooseLandingPage} 的注释）。
 */
function navigateToLandingPage() {
    var landing = state.landingPage || null;
    if (landing) {
        navigateTo(landing);
    } else {
        showNoPermissionPage();
    }
}

// ==================== 报表管理（管理员）====================
async function loadReportsList() {
    const tbody = document.getElementById('reportManageTableBody');
    if (!tbody) return;
    tbody.innerHTML = skeletonRow(6);

    try {
        // 分组信息分两半取，各自用**权限匹配**的端点：
        //   ① 每个报表「属于哪个分组」「是否隐藏」→ 直接用 /api/reports 自带的
        //      report.group / report.hidden（后端已按 report.manage 判定返回，见 ReportController#listReports）
        //   ② 分组名与筛选下拉 → /api/admin/groups/options（字典端点，登录即可）
        // 原先这里整体调 /api/admin/groups/config（需 groups.manage），于是只有 report.manage
        // 的自定义角色会 403 → 分组筛选下拉为空、分组名退化成显示 id。
        const [reportsRes, groupsRes] = await Promise.all([
            fetch('/api/reports', { credentials: 'include' }),
            fetch('/api/admin/groups/options', { credentials: 'include' })
        ]);
        if (reportsRes.status === 401) { window.location.href = '/login'; return; }
        const reports = await reportsRes.json();
        const groups = groupsRes.ok ? await groupsRes.json() : [];
        if (!groupsRes.ok) {
            console.warn('报表分组字典加载失败（HTTP ' + groupsRes.status + '），分组名将退化为显示 id');
        }

        if (reports.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="cell-empty">暂无报表</td></tr>';
            return;
        }

        // 筛选器：分组下拉
        const groupFilter = document.getElementById('reportGroupFilter');
        if (groupFilter && groupFilter.options.length <= 1) {
            groups.forEach(g => {
                const opt = document.createElement('option');
                opt.value = g.id; opt.textContent = g.name;
                groupFilter.appendChild(opt);
            });
        }

        tbody.innerHTML = reports.map(report => {
            const group = report.group || 'default';
            const groupName = (groups.find(g => g.id === group) || {}).name || group;
            const hidden = report.hidden === true;
            const paramCount = report.params ? report.params.length : 0;

            return `<tr data-group="${esc(group)}" data-name="${esc(report.name).toLowerCase()}" data-id="${esc(report.id).toLowerCase()}">
                <td>
                    <div style="display:flex;flex-direction:column;gap:2px;">
                        <strong>${esc(report.name)}</strong>
                        <small style="color:var(--gray-500);">${esc(report.id)}.sql</small>
                    </div>
                </td>
                <td>${esc(report.description || '-')}</td>
                <td><span class="tag">${esc(groupName)}</span></td>
                <td>${paramCount}</td>
                <td>${hidden ? '<span class="tag" style="color:var(--danger);background:#fef2f2;">已隐藏</span>' : '<span class="tag" style="color:var(--success);">可见</span>'}</td>
                <td>
                    <div style="display:flex;gap:4px;flex-wrap:wrap;">
                        <button class="btn-sm" onclick="editReportSql(${escAttr(report.id)})" title="编辑SQL">
                            <i class="ri-code-line"></i>
                        </button>
                        <button class="btn-sm" onclick="toggleReportHidden(${escAttr(report.id)}, ${!hidden})" title="${hidden ? '取消隐藏' : '隐藏'}">
                            <i class="${hidden ? 'ri-eye-line' : 'ri-eye-off-line'}"></i>
                        </button>
                        <button class="btn-sm" onclick="changeReportGroup(${escAttr(report.id)}, ${escAttr(group)})" title="修改分组">
                            <i class="ri-folder-line"></i>
                        </button>
                        <button class="btn-sm" onclick="deleteReport(${escAttr(report.id)}, this)" title="删除" style="color:var(--danger);">
                            <i class="ri-delete-bin-line"></i>
                        </button>
                    </div>
                </td>
            </tr>`;
        }).join('');
    } catch (e) {
        console.error('加载报表列表失败:', e);
        tbody.innerHTML = '<tr><td colspan="6" class="cell-empty error">加载失败</td></tr>';
    }
}

function filterReportsList() {
    const keyword = (document.getElementById('reportSearchInput')?.value || '').trim().toLowerCase();
    const groupFilter = document.getElementById('reportGroupFilter')?.value || '';
    const rows = document.querySelectorAll('#reportManageTableBody tr[data-id]');
    rows.forEach(row => {
        const matchName = !keyword || row.dataset.name.includes(keyword) || row.dataset.id.includes(keyword);
        const matchGroup = !groupFilter || row.dataset.group === groupFilter;
        row.style.display = (matchName && matchGroup) ? '' : 'none';
    });
}

async function deleteReport(reportId, btn) {
    if (!confirm(`注意：确定要删除报表 "${reportId}" 吗？\n\n对应的SQL文件也将被删除，此操作不可撤销！`)) return;
    await sensitiveFeedback(btn, async () => {
        try {
            const res = await fetch(`/api/admin/reports/${encodeURIComponent(reportId)}`, {
                method: 'DELETE',
                headers: csrfHeaders()
            });
            if (res.ok) {
                showToast('success', '报表已删除');
                return true;
            }
            const data = await res.json();
            showToast('error', data.error || '删除失败');
            return false;
        } catch (e) {
            showToast('error', '删除失败: ' + e.message);
            return false;
        }
    }, loadReportsList);
}

async function toggleReportHidden(reportId, hidden) {
    try {
        const res = await fetch(`/api/admin/reports/${encodeURIComponent(reportId)}/hidden`, {
            method: 'PUT',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({ hidden })
        });
        if (res.ok) {
            showToast('success', hidden ? '报表已隐藏' : '报表已取消隐藏');
            loadReportsList();
        } else {
            const data = await res.json();
            showToast('error', data.error || '操作失败');
        }
    } catch (e) {
        showToast('error', '操作失败: ' + e.message);
    }
}

async function editReportSql(reportId) {
    try {
        const res = await fetch(`/api/admin/reports/${encodeURIComponent(reportId)}/sql`, { credentials: 'include' });
        if (!res.ok) { showToast('error', '获取SQL失败'); return; }
        const data = await res.json();

        const html = `
            <div style="display:flex;flex-direction:column;gap:12px;">
                <div style="font-size:13px;color:var(--gray-500);">
                    <i class="ri-file-code-line"></i> 文件: ${esc(data.fileName)} &nbsp;|&nbsp; 报表: ${esc(data.reportName)}
                </div>
                <div class="form-group" style="margin:0;">
                    <label>SQL内容（YAML头部 + SQL语句）</label>
                    <textarea id="editSqlContent" style="width:100%;min-height:400px;font-family:monospace;font-size:13px;padding:12px;border:1px solid var(--gray-200);border-radius:var(--radius);resize:vertical;">${esc(data.content)}</textarea>
                </div>
                <div style="font-size:12px;color:var(--gray-500);">
                    修改后将自动备份原文件为 .bak，重新加载报表列表。
                </div>
            </div>`;

        showModal('编辑SQL - ' + reportId, html, async () => {
            const content = document.getElementById('editSqlContent').value;
            if (!content.trim()) { showToast('error', 'SQL内容不能为空'); return; }

            const putRes = await fetch(`/api/admin/reports/${encodeURIComponent(reportId)}/sql`, {
                method: 'PUT',
                credentials: 'include',
                headers: csrfHeaders(),
                body: JSON.stringify({ content })
            });
            if (putRes.ok) {
                showToast('success', 'SQL已更新，报表已重新加载');
                loadReportsList();
            } else {
                const err = await putRes.json();
                showToast('error', err.error || '更新失败');
            }
        });
    } catch (e) {
        showToast('error', '获取SQL失败: ' + e.message);
    }
}

/* ==================== 大屏指标（可视化平台）SQL ====================
 * 与上面的报表 SQL 编辑器同一套权限（report.manage）、同一个弹窗形态。
 * 差别在保存之后：报表每次实时查库，大屏读内存快照，所以这里要说清楚
 * 「排队重跑的是默认区间，其余区间按各自 interval-minutes 到期才更新」，
 * 否则改完盯着分时图看，会以为没生效。 */
var _screenMetrics = [];

async function loadScreenMetrics() {
    const tbody = document.getElementById('screenMetricTableBody');
    if (!tbody) return;
    if (!tbody._screenBound) {
        tbody._screenBound = true;
        tbody.addEventListener('click', function (e) {
            const btn = e.target.closest('[data-act="screen-metric-edit"]');
            if (btn) editScreenSql(btn.getAttribute('data-metric'));
        });
        const actions = tbody.closest('.card') && tbody.closest('.card').querySelector('.card-actions');
        if (actions && !actions._screenBound) {
            actions._screenBound = true;
            actions.addEventListener('click', function (e) {
                if (e.target.closest('[data-act="screen-metric-refresh"]')) loadScreenMetrics();
            });
        }
    }
    try {
        const res = await fetch('/api/admin/screen/metrics', { credentials: 'include' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        _screenMetrics = Array.isArray(data.metrics) ? data.metrics : [];
        renderScreenMetrics();
    } catch (e) {
        _screenMetrics = [];
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:24px;color:var(--danger);">'
            + '指标清单读取不到：' + esc(String(e.message || e)) + '</td></tr>';
    }
}

function renderScreenMetrics() {
    const tbody = document.getElementById('screenMetricTableBody');
    if (!tbody) return;
    if (!_screenMetrics.length) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:24px;color:var(--gray-400);">'
            + '服务端没有读到大屏指标（scripts/screen 目录为空或不存在）</td></tr>';
        return;
    }
    tbody.innerHTML = _screenMetrics.map(function (m) {
        const fetched = m.lastFetchedAt
            ? esc(m.lastFetchedAt) + ' · ' + (m.rowCount || 0) + ' 行'
              + (m.stale ? ' <span style="color:var(--warning);">数据延迟</span>' : '')
            : '<span style="color:var(--gray-400);">尚未取到数</span>';
        const err = m.lastError
            ? '<div style="font-size:12px;color:var(--danger);margin-top:2px;">'
              + esc(m.lastError) + '</div>'
            : '';
        return '<tr>'
            + '<td><strong>' + esc(m.name || m.id) + '</strong>'
            + '<div style="font-size:12px;color:var(--gray-500);">'
            + esc(m.description || m.id) + '</div>'
            + err + '</td>'
            + '<td><code style="font-size:12px;">' + esc(m.file || '') + '</code></td>'
            + '<td>' + (m.intervalMinutes ? m.intervalMinutes + ' 分钟' : '-') + '</td>'
            + '<td>' + fetched + '</td>'
            + '<td><button class="btn-sm" data-act="screen-metric-edit" data-metric="'
            + esc(m.id) + '">编辑SQL</button></td>'
            + '</tr>';
    }).join('');
}

async function editScreenSql(metricId) {
    let data;
    try {
        const res = await fetch(`/api/admin/screen/metrics/${encodeURIComponent(metricId)}/sql`,
            { credentials: 'include' });
        if (res.status === 404) { showToast('error', '大屏指标不存在'); return; }
        if (!res.ok) throw new Error('HTTP ' + res.status);
        data = await res.json();
    } catch (e) {
        showToast('error', '获取SQL失败: ' + (e && e.message ? e.message : e));
        return;
    }

    // 槽位清单来自列表接口（loadScreenMetrics 缓存的那份），不在前端另抄一份
    const metric = (_screenMetrics || []).find(function (x) { return x.id === metricId; }) || {};
    const slots = Array.isArray(metric.panelSlots) ? metric.panelSlots : [];
    const titleRows = slots.map(function (s) {
        const auto = s.autoRenamed
            ? ' <span style="font-size:11px;color:var(--gray-400);">（自动）</span>' : '';
        return '<div style="display:flex;align-items:center;gap:10px;">'
            + '<label style="width:180px;font-size:13px;color:var(--gray-600);">'
            + esc(s.defaultText) + auto + '</label>'
            + '<input type="text" class="screen-title-input" maxlength="20" data-slot="'
            + esc(s.key) + '" value="' + esc(s.current || '') + '"'
            + ' placeholder="' + esc(s.autoRenamed ? '留空 = 按所选区间自动切换' : '留空 = 用默认文字') + '"'
            + ' style="flex:1;padding:7px 10px;border:1px solid var(--gray-200);border-radius:var(--radius);font-size:13px;">'
            + '</div>';
    }).join('');

    const html = `
        <div style="display:flex;flex-direction:column;gap:12px;">
            <div style="font-size:13px;color:var(--gray-500);">
                <i class="ri-file-code-line"></i> 文件: scripts/screen/${esc(metricId)}.sql
            </div>
            ${titleRows ? `<div class="form-group" style="margin:0;">
                <label>面板标题（大屏上显示的文字）</label>
                <div style="display:flex;flex-direction:column;gap:6px;">${titleRows}</div>
                <div style="font-size:12px;color:var(--gray-500);margin-top:4px;line-height:1.7;">
                    标了<strong>（自动）</strong>的标题平时按所选区间在两种说法间自动切换
                    （如「今日门诊量」/「区间门诊量」），填了就以你填的为准，不再自动切换；清空后下一次轮询还原成默认。
                </div>
            </div>` : ''}
            <div class="form-group" style="margin:0;">
                <label>指标定义（YAML头部 + SQL语句）</label>
                <textarea id="editScreenSqlContent" style="width:100%;min-height:400px;font-family:monospace;font-size:13px;padding:12px;border:1px solid var(--gray-200);border-radius:var(--radius);resize:vertical;">${esc(data.content)}</textarea>
            </div>
            <div style="font-size:12px;color:var(--gray-500);line-height:1.7;">
                · YAML 头里的 <code>interval-minutes</code> 就是该指标的刷新间隔，改这里即可。<br>
                · <strong>必须输出「社区」列</strong>，且中文列别名要与口径对照表一致，别名写错不报错，只是那块面板空掉。<br>
                · 面板上的标题用上面的输入框改；YAML 里的 <code>name</code> 只是后台列表中的指标名，不影响大屏。<br>
                · 这里改的是取数语句、刷新间隔与标题，<strong>改不了面板布局</strong>；新增指标仍需前端配渲染函数。<br>
                · 保存后自动备份原文件为 .bak、立即重载指标定义，并把该指标排进串行队列重跑一次（默认区间）。
            </div>
        </div>`;

    showModal('编辑大屏指标SQL - ' + metricId, html, async () => {
        const content = document.getElementById('editScreenSqlContent').value;
        if (!content.trim()) { showToast('error', 'SQL内容不能为空'); return; }
        // 每次都带 panelTitles：全空 = 清除自定义标题（键缺失才表示「不动标题」，
        // 这里永远送全量输入框的值，语义上更直白，也避免「清空标题」点了没反应）
        const panelTitles = {};
        document.querySelectorAll('.screen-title-input').forEach(function (el) {
            panelTitles[el.getAttribute('data-slot')] = el.value;
        });
        try {
            const putRes = await fetch(`/api/admin/screen/metrics/${encodeURIComponent(metricId)}/sql`, {
                method: 'PUT',
                credentials: 'include',
                headers: csrfHeaders(),
                body: JSON.stringify({ content: content, panelTitles: panelTitles })
            });
            if (putRes.ok) {
                showToast('success', 'SQL已保存，指标定义已重载并排队重跑');
                loadScreenMetrics();
            } else {
                const err = await putRes.json().catch(() => ({}));
                showToast('error', err.error || '更新失败');
            }
        } catch (e) {
            showToast('error', '保存失败: ' + (e && e.message ? e.message : e));
        }
    });
}

async function changeReportGroup(reportId, currentGroup) {
    try {
        // 「有哪些分组」是字典读 → 用 /groups/options（登录即可），
        // 不要用 /api/admin/groups（需 groups.manage），否则只有 report.manage 的角色
        // 会 403，groups 变成对象，groups.map 抛错，弹窗直接打不开
        const res = await fetch('/api/admin/groups/options', { credentials: 'include' });
        if (!res.ok) throw new Error('加载分组失败（HTTP ' + res.status + '）');
        const groups = await res.json();
        if (!Array.isArray(groups)) throw new Error('分组数据格式异常');

        const options = groups.map(g =>
            `<option value="${esc(g.id)}" ${g.id === currentGroup ? 'selected' : ''}>${esc(g.name)} (${esc(g.id)})</option>`
        ).join('');

        const html = `
            <div style="display:flex;flex-direction:column;gap:12px;">
                <div style="font-size:13px;color:var(--gray-500);">报表: <strong>${esc(reportId)}</strong></div>
                <div class="form-group" style="margin:0;">
                    <label>选择分组</label>
                    <select id="changeGroupSelect" style="width:100%;padding:10px;border:1px solid var(--gray-200);border-radius:var(--radius);">
                        ${options}
                    </select>
                </div>
                <div style="font-size:12px;color:var(--gray-500);">
                    修改分组属于「分组管理」，需要该权限；若保存时提示权限不足，请让管理员在「角色管理」里勾上「分组管理」。
                </div>
            </div>`;

        showModal('修改分组', html, async () => {
            const groupId = document.getElementById('changeGroupSelect').value;
            const putRes = await fetch(`/api/admin/groups/assignment/${encodeURIComponent(reportId)}`, {
                method: 'PUT',
                credentials: 'include',
                headers: csrfHeaders(),
                body: JSON.stringify({ groupId })
            });
            if (putRes.ok) {
                showToast('success', '分组已更新');
                loadReportsList();
            } else if (putRes.status === 403) {
                // 403 的响应体没有 error 字段，直接说清缺哪个权限，别给一句「更新失败」
                showToast('error', '权限不足：修改报表分组需要「分组管理」权限');
            } else {
                const err = await putRes.json().catch(() => ({}));
                showToast('error', err.error || '更新失败');
            }
        });
    } catch (e) {
        showToast('error', '加载分组失败: ' + e.message);
    }
}

// ==================== 分组管理增强：查看分组内报表 ====================
async function viewGroupReports(groupId, groupName) {
    try {
        const [reportsRes, configRes] = await Promise.all([
            fetch('/api/reports', { credentials: 'include' }),
            fetch('/api/admin/groups/config', { credentials: 'include' })
        ]);
        const reports = await reportsRes.json();
        const config = await configRes.json();
        const assignments = config.assignments || {};

        const groupReports = reports.filter(r => (assignments[r.id] || 'default') === groupId);

        if (groupReports.length === 0) {
            showModal(`分组 "${groupName}" 的报表`, '<div style="text-align:center;padding:20px;color:var(--gray-500);">该分组暂无报表</div>', null);
            return;
        }

        const html = `
            <div style="max-height:400px;overflow-y:auto;">
                <table class="data-table" style="width:100%;">
                    <thead><tr><th>报表ID</th><th>报表名称</th><th>参数数</th><th>操作</th></tr></thead>
                    <tbody>
                        ${groupReports.map(r => `<tr>
                            <td><code>${esc(r.id)}</code></td>
                            <td>${esc(r.name)}</td>
                            <td>${r.params ? r.params.length : 0}</td>
                            <td>
                                <button class="btn-sm" onclick="selectReport(${escAttr(r.id)}); hideCommonModal();" title="查询此报表">
                                    <i class="ri-search-line"></i>
                                </button>
                                <button class="btn-sm" onclick="changeReportGroup(${escAttr(r.id)}, ${escAttr(groupId)})" title="移动到其他分组">
                                    <i class="ri-folder-transfer-line"></i>
                                </button>
                            </td>
                        </tr>`).join('')}
                    </tbody>
                </table>
            </div>`;

        showModal(`分组 "${groupName}" 的报表 (${groupReports.length})`, html, null);
    } catch (e) {
        showToast('error', '加载失败: ' + e.message);
    }
}

function hideCommonModal() {
    const modal = document.getElementById('commonModal');
    if (modal) modal.classList.remove('active');
}

// ==================== 确保函数在全局作用域 ====================
// 将需要从HTML onclick调用的函数绑定到window对象
window.showAddUserModal = showAddUserModal;
window.showAddGroupModal = showAddGroupModal;
window.showResetPwd = showResetPwd;
window.editUserGroups = editUserGroups;
window.editGroup = editGroup;
window.deleteUser = deleteUser;
window.deleteGroup = deleteGroup;
window.loadUsersList = loadUsersList;
window.loadGroupsList = loadGroupsList;
window.loadAuditPage = loadAuditPage;
window.queryAudit = queryAudit;
window.exportAuditCsv = exportAuditCsv;
window.showAuditDetail = showAuditDetail;
window.closeAuditDetail = closeAuditDetail;
window.loadLogsDirect = loadLogsDirect;
window.refreshLogs = refreshLogs;
window.exportLogs = exportLogs;
window.switchLogTab = switchLogTab;
window.filterLogLines = filterLogLines;
window.navigateTo = navigateTo;
window.doLogout = doLogout;
window.showChangePassword = showChangePassword;
window.refreshReports = refreshReports;
window.reloadReportDefinitions = reloadReportDefinitions;
window.loadDashboardStats = loadDashboardStats;
window.doQuery = doQuery;
window.changePageSize = changePageSize;
window.sortTable = sortTable;
window.copyCellContent = copyCellContent;
window.deleteReport = deleteReport;
window.toggleReportHidden = toggleReportHidden;
window.editReportSql = editReportSql;
window.changeReportGroup = changeReportGroup;
window.loadReportsList = loadReportsList;window.filterReportsList = filterReportsList;
window.viewGroupReports = viewGroupReports;


// ==================== 首次登录修改密码 ====================
async function checkFirstLogin() {
    try {
        const res = await apiFetch('/api/admin/current-user');
        if (res.ok) {
            const user = await res.json();
            if (user.mustChangePassword) {
                var modal = document.getElementById('firstLoginModal');
                if (modal) {
                    // 复位：三个框回到密文、清掉可能残留的一致性结论
                    resetPwdVisibility(['firstOldPwd', 'firstNewPwd', 'firstConfirmPwd']);
                    clearPwdMatchHint('firstPwdMatchHint');
                    modal.classList.add('active');
                    // 强制改密：禁止点遮罩关闭
                    modal.querySelectorAll('[data-dismiss],.modal-close,.btn-cancel').forEach(function(el) {
                        el.style.display = 'none';
                    });
                }
            }
        }
    } catch (e) {
        // 注意：不能静默吞掉：这次探测失败 → 强制改密弹窗不会出现，
        //    而后端 MustChangePasswordFilter 会把所有 /api/** 拦成
        //    `403 {"code":"MUST_CHANGE_PASSWORD"}`，用户只会看到
        //    「没权限 / 操作失败」，却不知道真正原因是「要先改密码」。
        //    （项目 MEMORY.md 记过：这个 403 看起来和权限不足一模一样。）
        //    宁可多一句提示，也不要让用户对着无解的 403 发呆。
        console.warn('检查首次登录状态失败，强制改密弹窗可能未弹出:', e);
        showToast('error', '无法确认账号状态，请刷新页面重试');
    }
}

async function submitFirstLoginPwd() {
    const oldPwd = document.getElementById('firstOldPwd').value;
    const newPwd = document.getElementById('firstNewPwd').value;
    const confirmPwd = document.getElementById('firstConfirmPwd').value;
    if (!oldPwd) { showToast('error', '请输入当前密码'); return; }
    if (!newPwd || newPwd.length < 8) { showToast('error', '新密码至少8位，包含字母和数字'); return; }
    if (newPwd !== confirmPwd) {
        // 除 toast 外，把结论钉在确认框下面，首次登录的用户最需要知道「是哪个框错了」
        updatePwdMatchHint('firstNewPwd', 'firstConfirmPwd', 'firstPwdMatchHint');
        document.getElementById('firstConfirmPwd').focus();
        showToast('error', '两次输入的密码不一致');
        return;
    }
    try {
        const res = await fetch('/api/admin/profile/password', {
            method: 'PUT', credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({ oldPassword: oldPwd, password: newPwd })
        });
        if (res.ok) {
            showToast('success', '密码修改成功');
            document.getElementById('firstLoginModal').classList.remove('active');
            window.location.reload();
        } else {
            const data = await res.json();
            showToast('error', data.error || '修改失败');
        }
    } catch (e) { showToast('error', '修改失败'); }
}

document.addEventListener('DOMContentLoaded', function() {
    setTimeout(checkFirstLogin, 500);
});


// ==================== 用户角色与数据权限（scope） ====================
async function showEditScope(username) {
    try {
        const [usersRes, rolesRes, commsRes, depsRes] = await Promise.all([
            fetch('/api/admin/users', { credentials: 'include' }),
            // 角色同理走字典端点（登录即可）：本页菜单声明 users.manage，
            // 用管理端点会让「只有 users.manage」的角色拿到空下拉，改不了归属。
            fetch('/api/admin/roles/options', { credentials: 'include' }),
            // 社区/科室都用 /enabled，这是后端声明的「各处下拉的统一选项来源」。
            // 早先这里调的是管理接口 /api/admin/communities（需 communities.manage），
            // 导致只有 users.manage 的角色 403 并静默降级；且它返回**含停用**社区，
            // 停用项会不带任何标注地出现在可勾选列表里（与「新增用户」弹窗不一致）。
            fetch('/api/admin/communities/enabled', { credentials: 'include' }),
            fetch('/api/admin/departments/enabled', { credentials: 'include' })
        ]);
        const users = usersRes.ok ? await usersRes.json() : [];
        const me = users.find(u => u.username === username);
        if (!me) { showToast('error', '用户不存在'); return; }
        const roles = rolesRes.ok ? await rolesRes.json() : [];
        const enabledComms = commsRes.ok ? await commsRes.json() : [];
        // 合并：启用社区 + 用户已有但已停用的社区（保持勾选可见，避免静默丢失）
        const extraComms = (me.allowedCommunities || [])
            .filter(code => !enabledComms.some(cm => cm.code === code))
            .map(code => ({ code: code, name: code + '（已停用）' }));
        const communities = enabledComms.concat(extraComms);
        // 科室同理：保存时按「勾选项」整体覆盖，已挂的停用科室必须补回来并标注，
        // 否则一保存就被静默删掉。
        const enabledDeps = depsRes.ok ? await depsRes.json() : [];
        const extraDeps = (me.allowedDepartments || [])
            .filter(code => !enabledDeps.some(d => d.code === code))
            .map(code => ({ code: code, name: code + '（已停用）' }));
        const departments = enabledDeps.concat(extraDeps);

        const curComm = new Set(me.allowedCommunities || []);
        const curDep = new Set(me.allowedDepartments || []);
        const roleOptions = roleOptionsHtml(roles, me.roleId);
        const commBoxes = communities.map(cm => `
            <label style="display:flex;align-items:center;gap:8px;padding:6px 8px;cursor:pointer;">
                <input type="checkbox" name="scopeCommunities" value="${esc(cm.code)}" ${curComm.has(cm.code) ? 'checked' : ''}>
                <span>${esc(cm.name)}</span>
            </label>`).join('');
        const depBoxes = departments.map(d => `
            <label style="display:flex;align-items:center;gap:8px;padding:6px 8px;cursor:pointer;">
                <input type="checkbox" name="scopeDepartments" value="${esc(d.code)}" ${curDep.has(d.code) ? 'checked' : ''}>
                <span>${esc(d.name)}${d.community ? '（' + esc(d.community) + '）' : ''}</span>
            </label>`).join('');

        const html = `
            <div style="display:flex;flex-direction:column;gap:16px;">
                <div class="form-group">
                    <label>角色（决定可点开的菜单）</label>
                    <select id="scopeRoleId" class="form-control">${roleOptions}</select>
                </div>
                <div class="form-group">
                    <label>数据权限：可查看社区（不勾选 = 不限）</label>
                    <div style="max-height:140px;overflow-y:auto;border:1px solid var(--gray-200);border-radius:var(--radius);padding:8px;">
                        ${commBoxes || '<p style="color:var(--gray-500);text-align:center;">社区字典为空</p>'}
                    </div>
                </div>
                <div class="form-group">
                    <label>数据权限：可查看科室（不勾选 = 不限，数据过滤为二期）</label>
                    <div style="max-height:140px;overflow-y:auto;border:1px solid var(--gray-200);border-radius:var(--radius);padding:8px;">
                        ${depBoxes || '<p style="color:var(--gray-500);text-align:center;">科室字典为空</p>'}
                    </div>
                </div>
                <p style="color:var(--gray-500);font-size:12px;">社区权限即时生效：该工号在报表里只能查到勾选社区的数据。大屏可见社区由「大屏号」单独配置（未配置时跟随此处勾选）。修改角色后对方刷新页面生效。</p>
            </div>
        `;

        showModal('角色与数据权限 - ' + username, html, async () => {
            const roleId = document.getElementById('scopeRoleId').value;
            const communities = Array.from(document.querySelectorAll('input[name="scopeCommunities"]:checked')).map(el => el.value);
            const departments = Array.from(document.querySelectorAll('input[name="scopeDepartments"]:checked')).map(el => el.value);
            const res = await fetch('/api/admin/users/' + encodeURIComponent(username) + '/scope', {
                method: 'PUT', credentials: 'include',
                headers: csrfHeaders(),
                body: JSON.stringify({ roleId, allowedCommunities: communities, allowedDepartments: departments })
            });
            if (res.ok) {
                showToast('success', '已保存');
                loadUsersList();
            } else {
                const data = await res.json().catch(() => ({}));
                showToast('error', data.error || '保存失败');
                return false;
            }
        });
    } catch (e) {
        showToast('error', '加载失败');
    }
}

// ==================== 社区管理 ====================
async function loadCommunitiesList() {
    const tbody = document.getElementById('communityTableBody');
    if (!tbody) return;
    tbody.innerHTML = skeletonRow(4);
    try {
        const res = await fetch('/api/admin/communities', { credentials: 'include' });
        if (res.status === 401) { window.location.href = '/login'; return; }
        const list = res.ok ? await res.json() : [];
        if (list.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" class="cell-empty">暂无社区</td></tr>';
            return;
        }
        tbody.innerHTML = list.map(cm => `<tr>
            <td><strong>${esc(cm.code)}</strong></td>
            <td>${esc(cm.name)}</td>
            <td><span class="badge ${cm.enabled ? 'badge-primary' : 'badge-default'}">${cm.enabled ? '启用' : '停用'}</span></td>
            <td>
                <button class="btn-sm" onclick='showCommunityModal(${escJsonAttr(cm)})'><i class="ri-edit-line"></i> 编辑</button>
                <button class="btn-sm" onclick='toggleCommunity(${escAttr(cm.code)}, ${cm.enabled}, this)'>${cm.enabled ? '停用' : '启用'}</button>
            </td>
        </tr>`).join('');
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="4" class="cell-empty error">加载失败</td></tr>';
    }
}

function showCommunityModal(edit) {
    const isEdit = !!edit;
    const html = `
        <div style="display:flex;flex-direction:column;gap:16px;">
            <div class="form-group">
                <label>社区代码（创建后不可改，1-4位字母/数字）</label>
                <input type="text" id="communityCode" class="form-control" value="${isEdit ? esc(edit.code) : ''}"
                    ${isEdit ? 'readonly style="background:var(--gray-100);"' : 'placeholder="如 F"'} maxlength="4">
            </div>
            <div class="form-group">
                <label>社区名称</label>
                <input type="text" id="communityName" class="form-control" value="${isEdit ? esc(edit.name) : ''}" placeholder="如 F社区">
            </div>
            ${isEdit ? `<div class="form-group">
                <label style="display:flex;align-items:center;gap:8px;cursor:pointer;">
                    <input type="checkbox" id="communityEnabled" ${edit.enabled ? 'checked' : ''}>
                    <span>启用（停用后不出现在报表下拉/大屏/权限多选中）</span>
                </label>
            </div>` : ''}
        </div>
    `;
    showModal(isEdit ? '编辑社区 - ' + edit.code : '新增社区', html, async () => {
        const name = document.getElementById('communityName').value.trim();
        const enabled = isEdit ? document.getElementById('communityEnabled').checked : true;
        if (!name) { showToast('error', '请填写名称'); return false; }
        const res = isEdit
            ? await fetch('/api/admin/communities/' + encodeURIComponent(edit.code), {
                method: 'PUT', credentials: 'include', headers: csrfHeaders(),
                body: JSON.stringify({ name, enabled })
            })
            : await fetch('/api/admin/communities', {
                method: 'POST', credentials: 'include', headers: csrfHeaders(),
                body: JSON.stringify({ code: document.getElementById('communityCode').value.trim(), name })
            });
        if (res.ok) { showToast('success', '已保存'); loadCommunitiesList(); }
        else {
            const data = await res.json().catch(() => ({}));
            showToast('error', data.error || '保存失败');
            return false;
        }
    });
}

async function toggleCommunity(code, currentEnabled, btn) {
    if (!confirm((currentEnabled ? '停用' : '启用') + '社区 ' + code + '？')) return;
    await sensitiveFeedback(btn, async () => {
        try {
            // apiFetch：CSRF/credentials 统一带上，401 直接跳登录页；断网/HTML 错误页由 catch 兜住
            const res = await apiFetch('/api/admin/communities/' + encodeURIComponent(code), {
                method: 'PUT',
                body: JSON.stringify({ enabled: !currentEnabled })
            });
            if (res.ok) { showToast('success', '已保存'); return true; }
            const d = await res.json().catch(() => ({}));
            showToast('error', d.error || '操作失败');
            return false;
        } catch (e) {
            console.error('社区启停失败:', e);
            showToast('error', '操作失败: ' + (e.message || '网络异常'));
            return false;
        }
    }, loadCommunitiesList);
}

// ==================== 科室管理 ====================
async function loadDepartmentsList() {
    const tbody = document.getElementById('departmentTableBody');
    if (!tbody) return;
    tbody.innerHTML = skeletonRow(8);
    try {
        const res = await fetch('/api/admin/departments', { credentials: 'include' });
        if (res.status === 401) { window.location.href = '/login'; return; }
        const list = res.ok ? await res.json() : [];
        if (list.length === 0) {
            tbody.innerHTML = '<tr><td colspan="8" class="cell-empty">暂无科室，可点击右上角「从HIS导入」</td></tr>';
            return;
        }
        tbody.innerHTML = list.map(d => `<tr>
            <td><strong>${esc(d.code)}</strong></td>
            <td>${esc(d.name)}</td>
            <td>${esc(d.category || '-')}</td>
            <td>${esc(d.community || '-')}</td>
            <td>${esc(d.contact || '-')}</td>
            <td>${esc(d.phone || '-')}</td>
            <td><span class="badge ${d.enabled ? 'badge-primary' : 'badge-default'}">${d.enabled ? '启用' : '停用'}</span></td>
            <td>
                <button class="btn-sm" onclick='showDepartmentModal(${escJsonAttr(d)})'><i class="ri-edit-line"></i> 编辑</button>
                <button class="btn-sm" onclick='toggleDepartment(${escAttr(d.code)}, ${d.enabled}, this)'>${d.enabled ? '停用' : '启用'}</button>
            </td>
        </tr>`).join('');
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="8" class="cell-empty error">加载失败</td></tr>';
    }
}

async function showDepartmentModal(edit) {
    const isEdit = !!edit;
    // 所属社区下拉选项：来自社区管理字典（启用项）；编辑时保留当前值（即使已停用）
    let communityOptions = '';
    try {
        const res = await fetch('/api/admin/communities/enabled', { credentials: 'include' });
        const list = res.ok ? await res.json() : [];
        const has = list.some(x => x.code === (isEdit ? edit.community : ''));
        communityOptions = '<option value="">未分配</option>'
            + (isEdit && edit.community && !has
                ? `<option value="${esc(edit.community)}" selected>${esc(edit.community)}（已停用）</option>` : '')
            + list.map(x => `<option value="${esc(x.code)}" ${isEdit && edit.community === x.code ? 'selected' : ''}>${esc(x.code)} ${esc(x.name)}</option>`).join('');
    } catch (e) {
        communityOptions = `<option value="${isEdit ? esc(edit.community || '') : ''}">${isEdit ? esc(edit.community || '未分配') : '未分配'}</option>`;
    }
    const html = `
        <div style="display:flex;flex-direction:column;gap:16px;">
            <div class="form-group">
                <label>科室编码（创建后不可改）</label>
                <input type="text" id="deptCode" class="form-control" value="${isEdit ? esc(edit.code) : ''}"
                    ${isEdit ? 'readonly style="background:var(--gray-100);"' : 'placeholder="如 0001 或 AYF"'} maxlength="20">
            </div>
            <div class="form-group">
                <label>科室名称</label>
                <input type="text" id="deptName" class="form-control" value="${isEdit ? esc(edit.name) : ''}" placeholder="如 A药房">
            </div>
            <div class="form-group">
                <label>类别</label>
                <select id="deptCategory" class="form-control">
                    ${['', '临床科室', '医辅科室', '行政科室', '其他科室'].map(cat =>
                        `<option value="${cat}" ${isEdit && (edit.category || '') === cat ? 'selected' : ''}>${cat || '未分类'}</option>`).join('')}
                </select>
            </div>
            <div class="form-group">
                <label>所属社区（选项来自社区管理字典）</label>
                <select id="deptCommunity" class="form-control">${communityOptions}</select>
            </div>
            <div style="display:flex;gap:12px;">
                <div class="form-group" style="flex:1;">
                    <label>联系人</label>
                    <input type="text" id="deptContact" class="form-control" value="${isEdit ? esc(edit.contact || '') : ''}">
                </div>
                <div class="form-group" style="flex:1;">
                    <label>电话</label>
                    <input type="text" id="deptPhone" class="form-control" value="${isEdit ? esc(edit.phone || '') : ''}">
                </div>
            </div>
            ${isEdit ? `<div class="form-group">
                <label style="display:flex;align-items:center;gap:8px;cursor:pointer;">
                    <input type="checkbox" id="deptEnabled" ${edit.enabled ? 'checked' : ''}>
                    <span>启用</span>
                </label>
            </div>` : ''}
        </div>
    `;
    showModal(isEdit ? '编辑科室 - ' + edit.code : '新增科室', html, async () => {
        const body = {
            name: document.getElementById('deptName').value.trim(),
            category: document.getElementById('deptCategory').value,
            community: document.getElementById('deptCommunity').value.trim(),
            contact: document.getElementById('deptContact').value.trim(),
            phone: document.getElementById('deptPhone').value.trim()
        };
        if (!body.name) { showToast('error', '请填写科室名称'); return false; }
        const res = isEdit
            ? await fetch('/api/admin/departments/' + encodeURIComponent(edit.code), {
                method: 'PUT', credentials: 'include', headers: csrfHeaders(),
                body: JSON.stringify(Object.assign({ enabled: document.getElementById('deptEnabled').checked }, body))
            })
            : await fetch('/api/admin/departments', {
                method: 'POST', credentials: 'include', headers: csrfHeaders(),
                body: JSON.stringify(Object.assign({ code: document.getElementById('deptCode').value.trim() }, body))
            });
        if (res.ok) { showToast('success', '已保存'); loadDepartmentsList(); }
        else {
            const data = await res.json().catch(() => ({}));
            showToast('error', data.error || '保存失败');
            return false;
        }
    });
}

async function toggleDepartment(code, currentEnabled, btn) {
    if (!confirm((currentEnabled ? '停用' : '启用') + '科室 ' + code + '？')) return;
    await sensitiveFeedback(btn, async () => {
        try {
            const res = await apiFetch('/api/admin/departments/' + encodeURIComponent(code), {
                method: 'PUT',
                body: JSON.stringify({ enabled: !currentEnabled })
            });
            if (res.ok) { showToast('success', '已保存'); return true; }
            const d = await res.json().catch(() => ({}));
            showToast('error', d.error || '操作失败');
            return false;
        } catch (e) {
            console.error('科室启停失败:', e);
            showToast('error', '操作失败: ' + (e.message || '网络异常'));
            return false;
        }
    }, loadDepartmentsList);
}

async function importDepartmentsHis() {
    if (!confirm('从 HIS 科室表导入科室字典？\n已存在的科室只更新名称/联系人/电话，不会覆盖本地设置的社区与启停状态。')) return;
    showToast('info', '正在从 HIS 导入...');
    try {
        const res = await fetch('/api/admin/departments/import-his', {
            method: 'POST', credentials: 'include', headers: csrfHeaders()
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            showToast('success', data.message || '导入完成');
            loadDepartmentsList();
        } else {
            showToast('error', data.error || '导入失败');
        }
    } catch (e) {
        showToast('error', '导入失败');
    }
}

// ==================== 角色管理 ====================
async function loadRolesList() {
    const tbody = document.getElementById('roleTableBody');
    if (!tbody) return;
    tbody.innerHTML = skeletonRow(5);
    try {
        const [rolesRes, permsRes] = await Promise.all([
            fetch('/api/admin/roles', { credentials: 'include' }),
            fetch('/api/admin/roles/permissions', { credentials: 'include' })
        ]);
        if (rolesRes.status === 401) { window.location.href = '/login'; return; }
        const list = rolesRes.ok ? await rolesRes.json() : [];
        const permList = permsRes.ok ? await permsRes.json() : [];
        const permLabel = {};
        permList.forEach(p => { permLabel[p.id] = p.label; });

        if (list.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="cell-empty">暂无角色</td></tr>';
            return;
        }
        // 角色是唯一事实来源：这里的角色列表 = 用户管理下拉 = 归属管理下拉。
        // 「使用情况」列把联动显式呈现出来（多少工号在用、被哪些职务带出），
        // 让管理员改/删角色前就知道影响面。
        tbody.innerHTML = list.map(r => {
            const perms = r.permissions || [];
            const posNames = r.usedByPositions || [];
            const usage = [
                `<div>${r.usageCount || 0} 个工号</div>`,
                posNames.length
                    ? `<div style="color:var(--gray-500);font-size:12px;">职务「${posNames.map(esc).join('」「')}」带出</div>`
                    : ''
            ].join('');
            return `<tr>
            <td><strong>${esc(r.name)}</strong><div style="color:var(--gray-500);font-size:12px;">${esc(r.id)}</div></td>
            <td><span class="badge ${r.builtin ? 'badge-primary' : 'badge-default'}">${r.builtin ? '内置' : '自定义'}</span></td>
            <td><div>${perms.length} 项</div><div style="color:var(--gray-500);font-size:12px;">${perms.length ? perms.map(p => esc(permLabel[p] || p)).join('、') : '无'}</div></td>
            <td>${usage}</td>
            <td>
                <button class="btn-sm" onclick='showRoleModal(${escJsonAttr(r)})'><i class="ri-edit-line"></i> 配置</button>
                ${r.builtin ? '' : `<button class="btn-sm" onclick='deleteRole(${escAttr(r.id)}, this)' style="color:var(--danger);"><i class="ri-delete-bin-line"></i> 删除</button>`}
            </td>
        </tr>`;
        }).join('');
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="5" class="cell-empty error">加载失败</td></tr>';
    }
}

async function showRoleModal(edit) {
    try {
        const res = await fetch('/api/admin/roles/permissions', { credentials: 'include' });
        const perms = res.ok ? await res.json() : [];
        const cur = new Set(edit ? (edit.permissions || []) : []);

        // 分组由后端声明（每项带 group 字段），这里只按「首现顺序」聚合。
        //   早先此处硬编码了 12 个权限点，漏掉 personnel.manage / announce.manage，
        //   导致打开「科主任」配置弹窗时看不到「人员管理」勾选框，直接保存会把它静默删掉。
        //   改为动态聚合 + 未归类兜底，新增权限点不可能再漏。
        const groups = [];
        const byName = new Map();
        for (const x of perms) {
            const g = x.group || '其它';
            if (!byName.has(g)) { byName.set(g, { title: g, items: [] }); groups.push(byName.get(g)); }
            byName.get(g).items.push(x);
        }
        const declared = groups.reduce((n, g) => n + g.items.length, 0);
        if (declared !== perms.length) {
            console.warn('[角色管理] 权限点分组数与总数不符', declared, perms.length);
        }

        const checks = groups.map(g => `
            <div style="margin-bottom:8px;">
                <div style="color:var(--gray-500);font-size:12px;margin-bottom:4px;">${esc(g.title)} <span style="color:var(--gray-500);">(${g.items.length})</span></div>
                ${g.items.map(x => `
                    <label style="display:flex;align-items:center;gap:8px;padding:5px 8px;cursor:pointer;">
                        <input type="checkbox" name="rolePerms" value="${esc(x.id)}" ${cur.has(x.id) ? 'checked' : ''}>
                        <span>${esc(x.label)} <span style="color:var(--gray-500);font-size:var(--fs-xs);">${esc(x.id)}</span></span>
                    </label>`).join('')}
            </div>`).join('');

        const html = `
            <div style="display:flex;flex-direction:column;gap:16px;">
                <div class="form-group">
                    <label>角色名称</label>
                    <input type="text" id="roleName" class="form-control" value="${edit ? esc(edit.name) : ''}" placeholder="如 社区操作员">
                </div>
                <div class="form-group">
                    <label>权限（决定该角色可点开哪些菜单与操作）</label>
                    <div style="max-height:300px;overflow-y:auto;border:1px solid var(--gray-200);border-radius:var(--radius);padding:10px;">
                        ${checks}
                    </div>
                    <p style="color:var(--gray-500);font-size:12px;margin-top:6px;">数据权限（可查看哪些社区/科室）不在这里配，在「用户管理 → 范围」按工号设置。修改后相关用户刷新页面生效。</p>
                </div>
            </div>
        `;
        showModal((edit ? '配置角色 - ' + edit.name : '新增角色'), html, async () => {
            const name = document.getElementById('roleName').value.trim();
            const permissions = Array.from(document.querySelectorAll('input[name="rolePerms"]:checked')).map(el => el.value);
            if (!name) { showToast('error', '请填写角色名称'); return false; }
            const res = edit
                ? await fetch('/api/admin/roles/' + encodeURIComponent(edit.id), {
                    method: 'PUT', credentials: 'include', headers: csrfHeaders(),
                    body: JSON.stringify({ name, permissions })
                })
                : await fetch('/api/admin/roles', {
                    method: 'POST', credentials: 'include', headers: csrfHeaders(),
                    body: JSON.stringify({ name, permissions })
                });
            if (res.ok) { showToast('success', '已保存'); loadRolesList(); }
            else {
                const data = await res.json().catch(() => ({}));
                showToast('error', data.error || '保存失败');
                return false;
            }
        });
    } catch (e) {
        showToast('error', '加载失败');
    }
}

async function deleteRole(id, btn) {
    if (!confirm('确认删除该角色？')) return;
    await sensitiveFeedback(btn, async () => {
        try {
            const res = await apiFetch('/api/admin/roles/' + encodeURIComponent(id), {
                method: 'DELETE'
            });
            if (res.ok) { showToast('success', '已删除'); return true; }
            const d = await res.json().catch(() => ({}));
            showToast('error', d.error || '删除失败');
            return false;
        } catch (e) {
            console.error('删除角色失败:', e);
            showToast('error', '删除失败: ' + (e.message || '网络异常'));
            return false;
        }
    }, loadRolesList);
}


// ==================== 人员管理 ====================
var _personnelAll = [];   // 全量人员
var _deptNameMap = {};    // 科室编码 → 名称

async function loadPersonnelList() {
    const tbody = document.getElementById('personnelTableBody');
    if (!tbody) return;
    tbody.innerHTML = skeletonRow(8);
    try {
        // 科室用 /enabled（登录即可访问）：本页只需 personnel.manage，
        // 而 GET /api/admin/departments 需要 departments.manage ，
        // 「科主任」角色只有 personnel.manage，调它会 403，
        // 科室名映射变空 → 科室列只显示编码、按科室名搜索失效、
        // 人员编辑弹窗的科室下拉为空（改不了科室）。
        const [pRes, dRes] = await Promise.all([
            fetch('/api/admin/personnel', { credentials: 'include' }),
            fetch('/api/admin/departments/enabled', { credentials: 'include' })
        ]);
        if (pRes.status === 401) { window.location.href = '/login'; return; }
        _personnelAll = pRes.ok ? await pRes.json() : [];
        const depts = dRes.ok ? await dRes.json() : [];
        _deptNameMap = {};
        depts.forEach(d => { _deptNameMap[d.code] = d.name; });
        renderPersonnelTable();
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="8" class="cell-empty error">加载失败</td></tr>';
    }
}

function renderPersonnelTable() {
    const tbody = document.getElementById('personnelTableBody');
    if (!tbody) return;
    const kw = (document.getElementById('personnelSearch') || {}).value || '';
    const k = kw.trim().toLowerCase();
    const list = _personnelAll.filter(d => {
        if (!k) return true;
        return (d.code || '').toLowerCase().includes(k)
            || (d.name || '').toLowerCase().includes(k)
            || (d.department || '').toLowerCase().includes(k)
            || (_deptNameMap[d.department] || '').toLowerCase().includes(k);
    });
    const countEl = document.getElementById('personnelCount');
    if (countEl) {
        // 「筛选后」原先写死成 0（数量在 filter 之前就算好了），搜出十几条也显示"筛选后 0"
        countEl.textContent = _personnelAll.length
            ? '共 ' + _personnelAll.length + ' 人' + (k ? '（筛选后 ' + list.length + '）' : '')
            : '';
    }
    if (list.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" class="cell-empty">' + (_personnelAll.length === 0 ? '暂无人员，可点击右上角「从HIS导入」' : '无匹配结果') + '</td></tr>';
        return;
    }
    tbody.innerHTML = list.map(d => {
        const deptName = _deptNameMap[d.department] || '';
        const deptHtml = d.department
            ? `<span class="tag" title="${esc(deptName)}">${esc(d.department)}${deptName ? ' ' + esc(deptName) : ''}</span>`
            : '<span style="color:var(--gray-500);">未分配</span>';
        return `<tr>
            <td><strong>${esc(d.code)}</strong></td>
            <td>${esc(d.name)}</td>
            <td>${esc(d.gender || '-')}</td>
            <td>${esc(d.title || '-')}</td>
            <td>${deptHtml}</td>
            <td>${esc(d.phone || '-')}</td>
            <td><span class="badge ${d.enabled ? 'badge-primary' : 'badge-default'}">${d.enabled ? '在用' : '停用'}</span></td>
            <td>
                <button class="btn-sm" onclick='showPersonnelModal(${escJsonAttr(d)})'><i class="ri-edit-line"></i> 编辑</button>
                <button class="btn-sm" onclick='togglePersonnel(${escAttr(d.code)}, ${d.enabled}, this)'>${d.enabled ? '停用' : '启用'}</button>
            </td>
        </tr>`;
    }).join('');
}

function showPersonnelModal(edit) {
    const isEdit = !!edit;
    const deptOptions = Object.keys(_deptNameMap).map(code =>
        `<option value="${esc(code)}" ${isEdit && edit.department === code ? 'selected' : ''}>${esc(code)} ${esc(_deptNameMap[code])}</option>`).join('');
    const codeLabel = isEdit ? '工号（创建后不可改）' : '工号（默认自动续号，可修改；如现有 1-100 且 90-95 空缺则默认补 90）';
    const html = `
        <div style="display:flex;flex-direction:column;gap:16px;">
            <div class="form-group">
                <label>${codeLabel}</label>
                <input type="text" id="personnelCode" class="form-control" value="${isEdit ? esc(edit.code) : ''}"
                    ${isEdit ? 'readonly style="background:var(--gray-100);"' : 'placeholder="自动生成中..."'} maxlength="20">
            </div>
            <div style="display:flex;gap:12px;">
                <div class="form-group" style="flex:1;">
                    <label>姓名</label>
                    <input type="text" id="personnelName" class="form-control" value="${isEdit ? esc(edit.name) : ''}">
                </div>
                <div class="form-group" style="width:120px;">
                    <label>性别</label>
                    <select id="personnelGender" class="form-control">
                        ${['', '男', '女'].map(g => `<option value="${g}" ${isEdit && (edit.gender || '') === g ? 'selected' : ''}>${g || '未知'}</option>`).join('')}
                    </select>
                </div>
            </div>
            <div style="display:flex;gap:12px;">
                <div class="form-group" style="flex:1;">
                    <label>职称</label>
                    <input type="text" id="personnelTitle" class="form-control" value="${isEdit ? esc(edit.title || '') : ''}" placeholder="如 主治医师">
                </div>
                <div class="form-group" style="flex:1;">
                    <label>所属科室（对应科室字典编码）</label>
                    <select id="personnelDept" class="form-control">
                        <option value="">未分配</option>
                        ${deptOptions}
                    </select>
                </div>
            </div>
            <div class="form-group">
                <label>联系电话</label>
                <input type="text" id="personnelPhone" class="form-control" value="${isEdit ? esc(edit.phone || '') : ''}">
            </div>
            ${isEdit ? `<div class="form-group">
                <label style="display:flex;align-items:center;gap:8px;cursor:pointer;">
                    <input type="checkbox" id="personnelEnabled" ${edit.enabled ? 'checked' : ''}>
                    <span>在用</span>
                </label>
            </div>` : ''}
        </div>
    `;
    if (!isEdit) {
        (async () => {
            try {
                const r = await fetch('/api/admin/personnel/next-code', { credentials: 'include' });
                if (r.ok) {
                    const d = await r.json();
                    const el = document.getElementById('personnelCode');
                    if (el && !el.value && d.nextCode) el.value = d.nextCode;
                }
            } catch (e) { /* 手输兜底 */ }
        })();
    }
    showModal(isEdit ? '编辑人员 - ' + edit.code : '新增人员', html, async () => {
        const code = document.getElementById('personnelCode').value.trim();
        if (!/^[A-Za-z0-9_-]{1,20}$/.test(code)) { showToast('error', '工号无效：1-20位字母/数字/下划线/中划线'); return false; }
        const body = {
            name: document.getElementById('personnelName').value.trim(),
            gender: document.getElementById('personnelGender').value,
            title: document.getElementById('personnelTitle').value.trim(),
            department: document.getElementById('personnelDept').value,
            phone: document.getElementById('personnelPhone').value.trim()
        };
        if (!body.name) { showToast('error', '请填写姓名'); return false; }
        const res = isEdit
            ? await fetch('/api/admin/personnel/' + encodeURIComponent(edit.code), {
                method: 'PUT', credentials: 'include', headers: csrfHeaders(),
                body: JSON.stringify(Object.assign({ enabled: document.getElementById('personnelEnabled').checked }, body))
            })
            : await fetch('/api/admin/personnel', {
                method: 'POST', credentials: 'include', headers: csrfHeaders(),
                body: JSON.stringify(Object.assign({ code: code }, body))
            });
        if (res.ok) { showToast('success', '已保存'); loadPersonnelList(); }
        else {
            const data = await res.json().catch(() => ({}));
            showToast('error', data.error || '保存失败');
            return false;
        }
    });
}

async function togglePersonnel(code, currentEnabled, btn) {
    if (!confirm((currentEnabled ? '停用' : '启用') + '人员 ' + code + '？')) return;
    await sensitiveFeedback(btn, async () => {
        try {
            const res = await apiFetch('/api/admin/personnel/' + encodeURIComponent(code), {
                method: 'PUT',
                body: JSON.stringify({ enabled: !currentEnabled })
            });
            if (res.ok) { showToast('success', '已保存'); return true; }
            const d = await res.json().catch(() => ({}));
            showToast('error', d.error || '操作失败');
            return false;
        } catch (e) {
            console.error('人员启停失败:', e);
            showToast('error', '操作失败: ' + (e.message || '网络异常'));
            return false;
        }
    }, loadPersonnelList);
}

async function importPersonnelHis() {
    if (!confirm('从 HIS 人员表导入人员字典？\n按所属科室编码自动匹配科室；已存在的人员只更新姓名/性别/职称/电话/拼音码/所属科室，不覆盖本地启停状态。')) return;
    showToast('info', '正在从 HIS 导入...');
    try {
        const res = await fetch('/api/admin/personnel/import-his', {
            method: 'POST', credentials: 'include', headers: csrfHeaders()
        });
        const data = await res.json().catch(() => ({}));
        if (res.ok) {
            showToast('success', data.message || '导入完成');
            loadPersonnelList();
        } else {
            showToast('error', data.error || '导入失败');
        }
    } catch (e) {
        showToast('error', '导入失败');
    }
}


// ==================== 信息发布栏 ====================
var _announcements = [];

async function loadAnnouncements() {
    const box = document.getElementById('announcementList');
    if (!box) return;
    box.innerHTML = '<div style="color:var(--gray-500);text-align:center;padding:20px;">加载中...</div>';
    try {
        const res = await fetch('/api/announcements', { credentials: 'include' });
        if (res.status === 401) { window.location.href = '/login'; return; }
        _announcements = res.ok ? await res.json() : [];
        renderAnnouncements();
    } catch (e) {
        box.innerHTML = '<div style="color:var(--gray-500);text-align:center;padding:20px;">加载失败</div>';
    }
}

function renderAnnouncements() {
    const box = document.getElementById('announcementList');
    if (!box) return;
    const canManage = state.currentPermissions && state.currentPermissions.has('announce.manage');
    if (_announcements.length === 0) {
        box.innerHTML = '<div style="color:var(--gray-500);text-align:center;padding:24px;">暂无发布信息</div>';
        return;
    }
    const typeMap = {
        update: { label: '系统更新', cls: 'badge-primary', icon: 'ri-refresh-line' },
        notice: { label: '通知', cls: 'badge-default', icon: 'ri-notification-2-line' },
        warning: { label: '注意事项', cls: 'badge-danger', icon: 'ri-error-warning-line' }
    };
    box.innerHTML = _announcements.map(a => {
        const t = typeMap[a.type] || typeMap.update;
        return `
        <div class="announcement-item" data-id="${esc(a.id)}" style="border-bottom:1px solid var(--gray-200);padding:12px 4px;">
            <div style="display:flex;align-items:center;gap:8px;cursor:pointer;" onclick="toggleAnnouncement(${escAttr(a.id)})">
                ${a.pinned ? '<i class="ri-pushpin-2-fill" style="color:var(--danger);" title="置顶"></i>' : ''}
                <span class="badge ${t.cls}"><i class="${t.icon}"></i> ${t.label}</span>
                <strong style="flex:1;">${esc(a.title)}</strong>
                <span style="color:var(--gray-500);font-size:12px;white-space:nowrap;">${esc(a.publishTime || '')} · ${esc(a.publisher || '')}</span>
            </div>
            <div class="announcement-content" id="ann-content-${esc(a.id)}" style="display:none;margin-top:8px;padding:10px 12px;background:var(--gray-100);border-radius:var(--radius);font-size:13px;line-height:1.7;white-space:pre-wrap;">${esc(a.content)}</div>
            ${canManage ? `
            <div style="margin-top:6px;display:flex;gap:6px;">
                <button class="btn-sm" onclick="toggleAnnouncementPin(${escAttr(a.id)}, ${a.pinned === true})">${a.pinned ? '取消置顶' : '置顶'}</button>
                <button class="btn-sm" onclick='showAnnouncementModal(${escJsonAttr(a)})'>编辑</button>
                <button class="btn-sm" onclick="deleteAnnouncement(${escAttr(a.id)}, this)" style="color:var(--danger);">删除</button>
            </div>` : ''}
        </div>`;
    }).join('');
}

function toggleAnnouncement(id) {
    const el = document.getElementById('ann-content-' + id);
    if (el) el.style.display = el.style.display === 'none' ? '' : 'none';
}

async function toggleAnnouncementPin(id, currentPinned) {
    try {
        const res = await apiFetch('/api/announcements/' + encodeURIComponent(id), {
            method: 'PUT',
            body: JSON.stringify({ pinned: !currentPinned })
        });
        if (res.ok) { showToast('success', currentPinned ? '已取消置顶' : '已置顶'); loadAnnouncements(); }
        else { const d = await res.json().catch(() => ({})); showToast('error', d.error || '操作失败'); }
    } catch (e) {
        console.error('置顶设置失败:', e);
        showToast('error', '操作失败: ' + (e.message || '网络异常'));
    }
}

async function deleteAnnouncement(id, btn) {
    if (!confirm('确认删除这条发布信息？')) return;
    await sensitiveFeedback(btn, async () => {
        try {
            const res = await apiFetch('/api/announcements/' + encodeURIComponent(id), {
                method: 'DELETE'
            });
            if (res.ok) { showToast('success', '已删除'); return true; }
            const d = await res.json().catch(() => ({}));
            showToast('error', d.error || '删除失败');
            return false;
        } catch (e) {
            console.error('删除发布信息失败:', e);
            showToast('error', '删除失败: ' + (e.message || '网络异常'));
            return false;
        }
    }, loadAnnouncements);
}

function showAnnouncementModal(edit) {
    const isEdit = !!edit;
    const html = `
        <div style="display:flex;flex-direction:column;gap:16px;">
            <div class="form-group">
                <label>标题 <span style="color:var(--danger);">*</span></label>
                <input type="text" id="annTitle" class="form-control" value="${isEdit ? esc(edit.title) : ''}" placeholder="如：v2.3 版本更新说明">
            </div>
            <div class="form-group">
                <label>类型</label>
                <select id="annType" class="form-control">
                    <option value="update" ${isEdit && edit.type === 'update' ? 'selected' : ''}>系统更新</option>
                    <option value="notice" ${isEdit && edit.type === 'notice' ? 'selected' : ''}>通知</option>
                    <option value="warning" ${isEdit && edit.type === 'warning' ? 'selected' : ''}>注意事项</option>
                </select>
            </div>
            <div class="form-group">
                <label>内容 <span style="color:var(--danger);">*</span></label>
                <textarea id="annContent" class="form-control" rows="6" placeholder="更新内容、通知事项、注意事项等（支持换行）">${isEdit ? esc(edit.content) : ''}</textarea>
            </div>
            <div class="form-group">
                <label style="display:flex;align-items:center;gap:8px;cursor:pointer;">
                    <input type="checkbox" id="annPinned" ${isEdit && edit.pinned ? 'checked' : ''}>
                    <span>置顶显示</span>
                </label>
            </div>
        </div>
    `;
    showModal(isEdit ? '编辑发布信息' : '发布信息', html, async () => {
        const title = document.getElementById('annTitle').value.trim();
        const content = document.getElementById('annContent').value.trim();
        if (!title) { showToast('error', '请填写标题'); return false; }
        if (!content) { showToast('error', '请填写内容'); return false; }
        const body = {
            title: title,
            content: content,
            type: document.getElementById('annType').value,
            pinned: document.getElementById('annPinned').checked
        };
        const res = isEdit
            ? await fetch('/api/announcements/' + encodeURIComponent(edit.id), {
                method: 'PUT', credentials: 'include', headers: csrfHeaders(),
                body: JSON.stringify(body)
            })
            : await fetch('/api/announcements', {
                method: 'POST', credentials: 'include', headers: csrfHeaders(),
                body: JSON.stringify(body)
            });
        if (res.ok) { showToast('success', isEdit ? '已保存' : '发布成功'); loadAnnouncements(); }
        else {
            const d = await res.json().catch(() => ({}));
            showToast('error', d.error || '操作失败');
            return false;
        }
    });
}

/* ==================== 操作审计（page-audit） ==================== */
/* 全部事件走 data-act 委托，不写内联 onclick，本项目的 CSP 里 script-src 早晚要去掉
 * 'unsafe-inline'，新代码不能再把它焊得更死（affiliation.js 已是这个模式）。 */
var _audit = { page: 1, pageSize: 50, total: 0, rows: [], seq: 0, inited: false };

function auditFilterValues() {
    return {
        user: (document.getElementById('auditUser') || {}).value || '',
        action: (document.getElementById('auditAction') || {}).value || '',
        status: (document.getElementById('auditStatus') || {}).value || '',
        dateFrom: (document.getElementById('auditFrom') || {}).value || '',
        dateTo: (document.getElementById('auditTo') || {}).value || '',
        keyword: (document.getElementById('auditKeyword') || {}).value || '',
        pageSize: (document.getElementById('auditPageSize') || {}).value || '50'
    };
}

function auditQueryString(f) {
    var p = new URLSearchParams();
    if (f.user) p.set('user', f.user);
    if (f.action) p.set('action', f.action);
    if (f.status) p.set('status', f.status);
    if (f.dateFrom) p.set('dateFrom', f.dateFrom);
    if (f.dateTo) p.set('dateTo', f.dateTo);
    if (f.keyword) p.set('keyword', f.keyword);
    p.set('page', String(_audit.page));
    p.set('pageSize', String(f.pageSize || _audit.pageSize));
    return p.toString();
}

async function loadAuditPage() {
    if (!_audit.inited) initAuditPage();
    await queryAudit();
}

function initAuditPage() {
    _audit.inited = true;
    var tbody = document.getElementById('auditTbody');
    if (tbody && !tbody._auditBound) {
        tbody._auditBound = true;
        tbody.addEventListener('click', function (e) {
            var btn = e.target.closest('[data-act]');
            if (!btn) return;
            var idx = parseInt(btn.getAttribute('data-index'), 10);
            if (btn.getAttribute('data-act') === 'audit-detail' && !isNaN(idx)) showAuditDetail(idx);
        });
    }
    var pager = document.getElementById('auditPager');
    if (pager && !pager._auditBound) {
        pager._auditBound = true;
        pager.addEventListener('click', function (e) {
            var btn = e.target.closest('[data-page-btn]');
            if (!btn || btn.disabled) return;
            var n = parseInt(btn.getAttribute('data-page-btn'), 10);
            if (!isNaN(n)) { _audit.page = n; queryAudit(); }
        });
    }
    var head = document.querySelector('#page-audit .header-actions');
    if (head && !head._auditBound) {
        head._auditBound = true;
        head.addEventListener('click', function (e) {
            var btn = e.target.closest('[data-act]');
            if (!btn) return;
            if (btn.getAttribute('data-act') === 'audit-refresh') queryAudit();
            if (btn.getAttribute('data-act') === 'audit-export') exportAuditCsv();
        });
    }
    var kw = document.getElementById('auditKeyword');
    if (kw && !kw._auditBound) {
        kw._auditBound = true;
        kw.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') { e.preventDefault(); _audit.page = 1; queryAudit(); }
        });
    }
}

async function queryAudit() {
    var f = auditFilterValues();
    _audit.pageSize = parseInt(f.pageSize, 10) || 50;
    var seq = ++_audit.seq;
    var tbody = document.getElementById('auditTbody');
    if (tbody) tbody.innerHTML = skeletonRow(9);
    try {
        var res = await apiFetch('/api/admin/audit?' + auditQueryString(f));
        if (seq !== _audit.seq) return;              // 慢响应不能盖掉新响应
        if (!res.ok) {
            var eb = await res.json().catch(function () { return {}; });
            renderAuditError(eb.error || ('加载失败（HTTP ' + res.status + '）'));
            return;
        }
        var data = await res.json();
        _audit.rows = data.rows || [];
        _audit.total = data.total || 0;
        fillAuditSelect('auditUser', data.operators, f.user, '全部工号');
        fillAuditSelect('auditAction', data.actions, f.action, '全部动作');
        renderAuditMeta(data);
        renderAuditRows();
    } catch (e) {
        if (seq !== _audit.seq) return;
        renderAuditError('加载失败：' + (e && e.message ? e.message : '网络错误'));
    }
}

function renderAuditError(msg) {
    _audit.rows = [];
    var tbody = document.getElementById('auditTbody');
    if (tbody) tbody.innerHTML = '<tr><td colspan="9" class="audit-empty error">' + esc(msg) + '</td></tr>';
    var pager = document.getElementById('auditPager');
    if (pager) pager.innerHTML = '';
    showToast('error', msg);
}

function fillAuditSelect(id, values, current, allLabel) {
    var el = document.getElementById(id);
    if (!el || !Array.isArray(values)) return;
    var keep = current || el.value || '';
    el.innerHTML = '<option value="">' + esc(allLabel) + '</option>'
        + values.map(function (v) {
            return '<option value="' + esc(v) + '"' + (v === keep ? ' selected' : '') + '>' + esc(v) + '</option>';
        }).join('');
    if (keep && values.indexOf(keep) < 0) el.value = '';
}

function renderAuditMeta(d) {
    var el = document.getElementById('auditMeta');
    if (el) {
        var bits = ['共 ' + (_audit.total || 0).toLocaleString() + ' 条',
                    '区间 ' + esc(d.dateFrom || '') + ' ~ ' + esc(d.dateTo || ''),
                    '留存 ' + (d.retentionDays || '?') + ' 天'];
        if (d.filesScanned != null) bits.push('扫描 ' + d.filesScanned + ' 个日档');
        // 白名单口径由后端给，页面只负责如实显示，写死在 HTML 里迟早和配置对不上
        if (d.exclusionNote) bits.push('白名单：' + esc(d.exclusionNote));
        el.innerHTML = bits.map(function (b) { return '<span>' + b + '</span>'; }).join('');
    }
    var warn = document.getElementById('auditWarn');
    if (warn) {
        var msgs = [];
        if (d.truncated) msgs.push('命中条数超过单次检索上限，已停止扫描，请缩小日期区间或加筛选，否则会误以为「就这些」。');
        if (d.malformed) msgs.push('有 ' + d.malformed + ' 行审计记录解析失败（通常是断电留下的半行），已跳过。');
        if (d.writeFailures) msgs.push('⚠ 审计落盘累计失败 ' + d.writeFailures + ' 次，有操作没存下来，请查磁盘与目录权限。');
        warn.innerHTML = msgs.map(esc).join('<br>');
        warn.style.display = msgs.length ? 'block' : 'none';
    }
}

var AUDIT_ACTION_LABELS = {
    loginSuccess: '登录', loginFailed: '登录失败', logout: '登出',
    query: '报表查询', queryAll: '报表全量查询', export: '导出数据',
    auditView: '查看审计', auditExport: '导出审计台账'
};

function auditActionLabel(a) {
    if (!a) return '';
    return AUDIT_ACTION_LABELS[a] ? AUDIT_ACTION_LABELS[a] + '（' + a + '）' : a;
}

function renderAuditRows() {
    var tbody = document.getElementById('auditTbody');
    if (!tbody) return;
    if (!_audit.rows.length) {
        tbody.innerHTML = '<tr><td colspan="9" class="audit-empty">该条件下没有审计记录</td></tr>';
        renderAuditPager();
        return;
    }
    tbody.innerHTML = _audit.rows.map(function (r, i) {
        var st = r.success == null ? '<span class="audit-unknown">未知</span>'
            : (r.success ? '<span class="audit-ok">成功</span>' : '<span class="audit-fail">失败/被拒</span>');
        return '<tr>'
            + '<td class="audit-time">' + esc(r.time || '') + '</td>'
            + '<td>' + esc(r.operator || '') + '</td>'
            + '<td>' + esc(auditActionLabel(r.action)) + '</td>'
            + '<td>' + esc(r.reportId || '') + '</td>'
            + '<td>' + st + '</td>'
            + '<td class="audit-num">' + (r.rowCount == null ? '' : esc(String(r.rowCount))) + '</td>'
            + '<td class="audit-num">' + (r.durationMs == null ? '' : esc(r.durationMs + 'ms')) + '</td>'
            + '<td>' + esc(r.clientIp || '') + '</td>'
            + '<td><button class="btn-sm" data-act="audit-detail" data-index="' + i + '">详情</button></td>'
            + '</tr>';
    }).join('');
    renderAuditPager();
}

function renderAuditPager() {
    var el = document.getElementById('auditPager');
    if (!el) return;
    var pages = Math.max(1, Math.ceil(_audit.total / _audit.pageSize));
    if (pages <= 1) { el.innerHTML = ''; return; }
    var cur = _audit.page;
    var from = Math.max(1, cur - 3), to = Math.min(pages, from + 6);
    from = Math.max(1, Math.min(from, to - 6));
    var html = '<button class="page-btn" data-page-btn="' + (cur - 1) + '"' + (cur <= 1 ? ' disabled' : '') + '>上一页</button>';
    for (var i = from; i <= to; i++) {
        html += '<button class="page-btn' + (i === cur ? ' active' : '') + '" data-page-btn="' + i + '">' + i + '</button>';
    }
    html += '<button class="page-btn" data-page-btn="' + (cur + 1) + '"' + (cur >= pages ? ' disabled' : '') + '>下一页</button>';
    html += '<span class="page-info">第 ' + cur + ' / ' + pages + ' 页</span>';
    el.innerHTML = html;
}

function showAuditDetail(index) {
    var r = _audit.rows[index];
    if (!r) return;
    var body = document.getElementById('auditDetailBody');
    if (!body) return;
    var known = ['time', 'operator', 'action', 'reportId', 'success', 'rowCount',
                 'durationMs', 'clientIp', 'peerAddr', 'ts'];
    var extra = Object.keys(r).filter(function (k) { return known.indexOf(k) < 0; });
    var html = '<dl class="audit-dl">'
        + '<dt>时间</dt><dd>' + esc(r.time || '') + '</dd>'
        + '<dt>工号</dt><dd>' + esc(r.operator || '（无上下文）') + '</dd>'
        + '<dt>动作</dt><dd>' + esc(auditActionLabel(r.action)) + '</dd>'
        + '<dt>结果</dt><dd>' + esc(r.success == null ? '未知' : (r.success ? '成功' : '失败/被拒')) + '</dd>';
    if (r.reportId != null) html += '<dt>报表</dt><dd>' + esc(r.reportId) + '</dd>';
    if (r.rowCount != null) html += '<dt>行数</dt><dd>' + esc(String(r.rowCount)) + '</dd>';
    if (r.durationMs != null) html += '<dt>耗时</dt><dd>' + esc(r.durationMs + 'ms') + '</dd>';
    html += '<dt>来源 IP</dt><dd>' + esc(r.clientIp || '') + ' / 对端 ' + esc(r.peerAddr || '') + '</dd>';
    if (extra.length) {
        html += '<dt class="audit-dl-span">其他字段</dt><dd class="audit-dl-span"><ul>'
            + extra.map(function (k) {
                return '<li><code>' + esc(k) + '</code> = ' + esc(String(r[k])) + '</li>';
            }).join('') + '</ul></dd>';
    }
    html += '</dl><p class="audit-note">⚠ 审计不记录报表参数值（参数里可能出现身份证号、患者姓名）。'
        + '这里回答的是「谁查了哪张报表、拿了多少行」；具体查了哪个条件不在审计范围内。</p>';
    body.innerHTML = html;
    var modal = document.getElementById('auditDetailModal');
    if (modal) modal.classList.add('active');
}

function closeAuditDetail() {
    var modal = document.getElementById('auditDetailModal');
    if (modal) modal.classList.remove('active');
}

function exportAuditCsv() {
    var f = auditFilterValues();
    var p = new URLSearchParams();
    ['user', 'action', 'status', 'dateFrom', 'dateTo', 'keyword'].forEach(function (k) {
        if (f[k]) p.set(k, f[k]);
    });
    window.open('/api/admin/audit/export?' + p.toString(), '_blank');
}

document.addEventListener('click', function (e) {
    var btn = e.target.closest('#page-audit [data-act]');
    if (!btn) return;
    var act = btn.getAttribute('data-act');
    if (act === 'audit-search') { _audit.page = 1; queryAudit(); }
    else if (act === 'audit-reset') {
        ['auditUser', 'auditAction', 'auditStatus', 'auditFrom', 'auditTo', 'auditKeyword']
            .forEach(function (id) { var el = document.getElementById(id); if (el) el.value = ''; });
        _audit.page = 1; queryAudit();
    } else if (act === 'audit-detail-close') closeAuditDetail();
    else if (act === 'audit-change') { _audit.page = 1; queryAudit(); }
});
