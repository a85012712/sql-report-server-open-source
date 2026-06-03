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
    cachedAllData: null,
    cachedColumns: [],
    pageSize: 100,
    lastQueryParamsKey: '',
    currentUserRole: '',
    currentUsername: '',
    resetPwdTarget: '',
    defaultDataSourceId: 'default',
    recentReports: JSON.parse(localStorage.getItem('recentReports') || '[]'),
    allowedReportIds: null, // 当前用户有权访问的报表ID集合
    _queryGeneration: 0,
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

// 输入安全校验
function validateInput(value, options) {
    const opts = options || {};
    const maxLen = opts.maxLength || 200;
    const pattern = opts.pattern;
    if (value.length > maxLen) return '输入过长（最多' + maxLen + '字符）';
    if (pattern && !pattern.test(value)) return opts.patternMsg || '输入格式不正确';
    return null;
}

// 密码强度校验
function checkPasswordStrength(pwd) {
    if (pwd.length < 6) return { level: 0, msg: '至少6位' };
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

function navigateTo(page) {
    state.currentPage = page;

    // 更新导航状态
    elements.navItems.forEach(item => {
        item.classList.toggle('active', item.dataset.page === page);
    });

    // 更新页面显示
    elements.contentPages.forEach(p => p.classList.remove('active'));
    const targetPage = document.getElementById(`page-${page}`);
    if (targetPage) targetPage.classList.add('active');

    // 更新面包屑
    const pageNames = {
        dashboard: '工作台', reports: '报表中心', 'report-query': '报表查询',
        users: '用户管理', groups: '分组管理', logs: '系统日志',
        sqlimport: 'SQL导入', datasource: '数据源管理'
    };
    if (page === 'report-query' && state.currentReport) {
        elements.breadcrumb.innerHTML = `<span style="cursor:pointer;color:var(--primary);" onclick="navigateTo('reports')">报表中心</span><span style="margin:0 8px;color:var(--gray-400);">/</span><span>${esc(state.currentReport.name)}</span>`;
    } else {
        elements.breadcrumb.innerHTML = `<span>${pageNames[page] || page}</span>`;
    }

    // 移动端关闭侧边栏
    if (window.innerWidth <= 1024) {
        elements.sidebar.classList.remove('mobile-open');
    }

    // 页面特定加载
    if (page === 'reports') loadReports();
    else if (page === 'datasource') loadDataSources();
    else if (page === 'users') loadUsersList();
    else if (page === 'groups') loadGroupsList();
    else if (page === 'reportmanage') loadReportsList();
    else if (page === 'logs') loadLogsDirect();
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

// ==================== 分页滚动位置保持 ====================
let lastScrollPosition = { top: 0, left: 0 };

function saveScrollPosition() {
    const tableScroll = document.querySelector('.table-scroll');
    if (tableScroll) {
        lastScrollPosition.top = tableScroll.scrollTop;
        lastScrollPosition.left = tableScroll.scrollLeft;
    }
}

function restoreScrollPosition() {
    const tableScroll = document.querySelector('.table-scroll');
    if (tableScroll) {
        requestAnimationFrame(() => {
            tableScroll.scrollTop = lastScrollPosition.top;
            tableScroll.scrollLeft = lastScrollPosition.left;
        });
    }
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
        if (res.ok) {
            const user = await res.json();
            state.currentUserRole = user.role;
            state.currentUsername = user.username;

            const els = {
                userName: user.username,
                userRole: user.role === 'ADMIN' ? '管理员' : '普通用户',
                userAvatar: user.username.charAt(0).toUpperCase(),
                userAvatarSm: user.username.charAt(0).toUpperCase(),
                userDropdownName: user.username
            };

            Object.entries(els).forEach(([id, val]) => {
                const el = document.getElementById(id);
                if (el) el.textContent = val;
            });

            if (user.role === 'ADMIN') {
                document.querySelectorAll('.admin-only').forEach(el => el.style.display = '');
            }
        }
    } catch (e) {
        console.error('加载用户信息失败:', e);
    }
}

// ==================== 报表列表 ====================
async function loadReports() {
    try {
        const res = await fetch('/api/reports');
        if (res.status === 401) { window.location.href = '/login'; return; }
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
    const isAdmin = state.currentUserRole === 'ADMIN';

    container.innerHTML = reports.map((report, index) => {
        const color = iconColors[index % iconColors.length];
        const icon = icons[index % icons.length];
        const paramCount = report.params ? report.params.length : 0;

        const adminActions = isAdmin ? `
            <div class="report-admin-actions" style="display:flex;gap:4px;margin-top:8px;padding-top:8px;border-top:1px solid var(--gray-100);">
                <button class="btn-sm" onclick="event.stopPropagation(); editReportSql('${report.id}')" title="编辑SQL" style="font-size:12px;">
                    <i class="ri-code-line"></i>
                </button>
                <button class="btn-sm" onclick="event.stopPropagation(); toggleReportHidden('${report.id}', true)" title="隐藏" style="font-size:12px;">
                    <i class="ri-eye-off-line"></i>
                </button>
                <button class="btn-sm" onclick="event.stopPropagation(); changeReportGroup('${report.id}')" title="修改分组" style="font-size:12px;">
                    <i class="ri-folder-line"></i>
                </button>
                <button class="btn-sm" onclick="event.stopPropagation(); deleteReport('${report.id}')" title="删除" style="font-size:12px;color:var(--danger);">
                    <i class="ri-delete-bin-line"></i>
                </button>
            </div>` : '';

        return `
            <div class="report-card" onclick="selectReport('${report.id}')">
                <div class="report-card-header">
                    <div class="report-icon ${color}"><i class="${icon}"></i></div>
                </div>
                <h4>${esc(report.name)}</h4>
                <p>${esc(report.description || '暂无描述')}</p>
                <div class="report-meta">
                    <span><i class="ri-filter-3-line"></i> ${paramCount} 个筛选条件</span>
                </div>
                <div class="report-actions">
                    <button class="btn-sm primary" onclick="event.stopPropagation(); selectReport('${report.id}')">
                        <i class="ri-arrow-right-line"></i> 选择
                    </button>
                </div>
                ${adminActions}
            </div>`;
    }).join('');
}

async function refreshReports(e) {
    let btn = null;
    if (e && e.target) {
        btn = e.target.closest('.btn-sm') || e.target.closest('.btn-primary');
    }
    if (!btn) {
        // 被程序调用（如navigateTo），直接刷新无按钮动画
        try {
            const h = {}; h[getCsrfHeader()] = getCsrfToken();
            await fetch('/api/reports/refresh', { method: 'POST', headers: h });
            await loadReports();
            showToast('success', '报表列表已刷新');
        } catch (err) {
            showToast('error', '刷新失败: ' + err.message);
        }
        return;
    }
    const originalText = btn.innerHTML;

    try {
        btn.innerHTML = '<span class="loading"></span> 刷新中...';
        btn.disabled = true;

        const h = {}; h[getCsrfHeader()] = getCsrfToken();
        await fetch('/api/reports/refresh', { method: 'POST', headers: h });
        await loadReports();

        showToast('success', '报表列表已刷新');
    } catch (e) {
        showToast('error', '刷新失败: ' + e.message);
    } finally {
        btn.innerHTML = originalText;
        btn.disabled = false;
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
        state.cachedAllData = null;
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
                if (p.type === 'select' && p.source) loadOptions(id, p.name);
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

async function loadOptions(reportId, paramName) {
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
        tbody.innerHTML = '<tr><td colspan="2" style="text-align:center;color:var(--gray-400);padding:40px;">请从左侧选择报表开始查询</td></tr>';
        return;
    }
    tbody.innerHTML = visible.map(r => `
        <tr>
            <td><i class="ri-file-chart-line" style="color:var(--primary);margin-right:8px;"></i>${esc(r.name)}</td>
            <td>
                <button class="btn-sm primary" onclick="selectReport('${esc(r.id)}')">
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
    if (!page) page = 1;

    // 防重复点击
    if (state._querying) return;
    state._querying = true;

    const params = collectParams();
    const paramsKey = getParamsKey(params);

    if (state.cachedAllData && paramsKey === state.lastQueryParamsKey) {
        // 分页时保存滚动位置
        saveScrollPosition();
        state.currentPageNum = page;
        renderPage(page);
        // 恢复滚动位置
        restoreScrollPosition();
        state._querying = false;
        return;
    }

    state.currentParams = params;
    state.currentPageNum = 1;
    state.lastQueryParamsKey = paramsKey;
    state.cachedAllData = null;
    state.cachedColumns = [];
    state.currentSortColumn = -1;
    state.currentSortDirection = 'none';

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
    queryBtn.disabled = true;
    exportBtn.disabled = true;

    try {
        const res = await fetch(`/api/report/${state.currentReport.id}/query-all`, {
            method: 'POST',
            headers: csrfHeaders(),
            body: JSON.stringify({ params: params })
        });

        // 丢弃过期响应
        if (generation !== state._queryGeneration) return;

        if (res.status === 401) { window.location.href = '/login'; return; }
        const data = await res.json();

        if (data.error) {
            showError(data.error);
            return;
        }

        state.cachedAllData = data.rows || [];
        state.cachedColumns = data.columns || [];
        renderPage(1);
    } catch (e) {
        if (generation !== state._queryGeneration) return;
        showError('请求失败: ' + e.message);
        state._querying = false;
    } finally {
        if (generation === state._queryGeneration) {
            document.getElementById('loadingState').classList.remove('show');
            queryBtn.disabled = false;
            exportBtn.disabled = false;
            state._querying = false;
        }
    }
}

function changePageSize(val) {
    state.pageSize = Math.max(1, parseInt(val, 10)) || 100;
    renderPage(1);
}

function renderPage(page) {
    if (!state.cachedAllData || !state.cachedColumns) return;

    var total = state.cachedAllData.length;
    state.totalPages = Math.ceil(total / state.pageSize);
    if (state.totalPages < 1) state.totalPages = 1;
    if (page < 1) page = 1;
    if (page > state.totalPages) page = state.totalPages;
    state.currentPageNum = page;

    document.getElementById('resultInfo').textContent = '共 ' + total.toLocaleString() + ' 条记录';

    // 更新排序指示
    var thead = document.getElementById('tableHead');
    thead.innerHTML = '<tr>' + state.cachedColumns.map(function(c, index) {
        var sortClass = '';
        if (index === state.currentSortColumn) {
            sortClass = state.currentSortDirection === 'asc' ? ' sort-asc' : ' sort-desc';
        }
        return '<th data-sort="true" onclick="sortTable(' + index + ')" class="sortable' + sortClass + '" title="点击排序">' + esc(c) + '</th>';
    }).join('') + '</tr>';

    const tbody = document.getElementById('tableBody');
    if (total === 0) {
        tbody.innerHTML = `<tr><td colspan="${state.cachedColumns.length}" style="text-align:center;color:var(--gray-400);padding:60px;">
            <div style="font-size:48px;margin-bottom:16px;"><i class="ri-inbox-line"></i></div>
            <div>暂无数据</div>
        </td></tr>`;
    } else {
        const start = (page - 1) * state.pageSize;
        const end = Math.min(start + state.pageSize, total);
        const pageRows = state.cachedAllData.slice(start, end);
        tbody.innerHTML = pageRows.map(row =>
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
    if (!statsEl || !state.cachedAllData) return;

    const total = state.cachedAllData.length;
    const page = state.currentPageNum;
    const startRow = (page - 1) * state.pageSize + 1;
    const endRow = Math.min(page * state.pageSize, total);

    statsEl.innerHTML = `
        <div class="stat-item"><i class="ri-bar-chart-line"></i><span>总记录:</span><span class="stat-value">${total.toLocaleString()}</span></div>
        <div class="stat-item"><i class="ri-file-list-3-line"></i><span>列数:</span><span class="stat-value">${state.cachedColumns.length}</span></div>
        <div class="stat-item"><i class="ri-pages-line"></i><span>当前页:</span><span class="stat-value">${startRow}-${endRow}</span></div>`;
}

// ==================== 表格排序 ====================
function sortTable(columnIndex) {
    if (!state.cachedAllData) return;

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

    state.cachedAllData.sort((a, b) => {
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

    renderPage(1);
    showToast('info', `已按${state.currentSortDirection === 'asc' ? '升序' : '降序'}排列`);
}

// ==================== 导出功能 ====================
async function doExport() {
    if (!state.currentReport) return;
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

        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = state.currentReport.name + '.xlsx';
        a.click();
        URL.revokeObjectURL(url);

        showToast('success', '导出成功');
    } catch (e) {
        showError('导出失败: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalContent;
        state._exporting = false;
    }
}

async function exportReport(reportId) {
    try {
        const res = await fetch(`/api/report/${reportId}`);
        const report = await res.json();

        const params = {};
        if (report.params) {
            report.params.forEach(p => {
                if (p.defaultValue) params[p.name] = p.defaultValue;
            });
        }

        const exportRes = await fetch(`/api/report/${reportId}/export`, {
            method: 'POST',
            headers: csrfHeaders(),
            body: JSON.stringify({ params: params })
        });

        if (exportRes.ok) {
            const blob = await exportRes.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = report.name + '.xlsx';
            a.click();
            URL.revokeObjectURL(url);
            showToast('success', '导出成功');
        } else {
            showToast('error', '导出失败');
        }
    } catch (e) {
        showToast('error', '导出失败: ' + e.message);
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
function showAdminPanel() {
    navigateTo('users');
}

async function deleteUser(username) {
    if (!confirm(`⚠️ 确定要删除用户 "${username}" 吗？\n\n此操作不可恢复！`)) return;

    try {
        const res = await fetch(`/api/admin/users/${encodeURIComponent(username)}`, {
            method: 'DELETE',
            headers: csrfHeaders()
        });

        if (res.ok) {
            showToast('success', '用户已删除');
            loadUsersList();
        } else {
            showToast('error', '删除失败');
        }
    } catch (e) {
        showToast('error', '删除失败: ' + e.message);
    }
}

// ==================== 修改密码 ====================
function showChangePassword() {
    document.getElementById('changePwdUsername').value = state.currentUsername;
    document.getElementById('changePwdOld').value = '';
    document.getElementById('changePwdNew').value = '';
    document.getElementById('changePwdConfirm').value = '';
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

    if (!newPwd || newPwd.length < 6) {
        showToast('error', '新密码至少6位');
        return;
    }

    if (newPwd !== confirmPwd) {
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
function showGroupPanel() {
    document.getElementById('groupModal').classList.add('active');
    loadGroups();
}

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
                        <button class="action-btn danger" onclick="deleteGroup('${esc(g.id)}')">删除</button>
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

async function deleteGroup(groupId) {
    if (!confirm(`⚠️ 确定要删除分组 "${groupId}" 吗？\n\n该分组下的报表将变为无分组状态！`)) return;

    try {
        const res = await fetch(`/api/admin/groups/${encodeURIComponent(groupId)}`, {
            method: 'DELETE',
            headers: csrfHeaders()
        });

        if (res.ok) {
            showToast('success', '分组已删除');
            loadGroups();
        } else {
            showToast('error', '删除失败');
        }
    } catch (e) {
        showToast('error', '删除失败: ' + e.message);
    }
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
        const res = await fetch('/api/admin/groups', { credentials: 'include' });
        const groups = await res.json();
        const select = document.getElementById('sqlImportGroup');
        if (select) {
            select.innerHTML = groups.map(g => `<option value="${g.id}">${g.name}</option>`).join('');
        }
    } catch (e) {
        console.error('加载分组失败:', e);
    }
}

function previewSqlImport() {
    const input = document.getElementById('sqlImportInput').value;
    if (!input.trim()) {
        showToast('error', '请粘贴SQL语句');
        return;
    }

    const paramRegex = /\[([^\]]+)\]/g;
    const params = [];
    let match;
    while ((match = paramRegex.exec(input)) !== null) {
        params.push(match[1]);
    }

    let converted = input;
    const paramMap = {};
    params.forEach(p => {
        const paramName = p.replace(/[^\w一-龥]/g, '_').toLowerCase();
        paramMap[p] = paramName;
        converted = converted.replace(new RegExp(`\\[${p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\]`, 'g'), `:${paramName}`);
    });

    state._sqlImportConverted = converted;
    state._sqlImportPreview = params;

    const paramsEl = document.getElementById('sqlImportParams');
    if (params.length > 0) {
        paramsEl.innerHTML = `
            <div class="param-preview-list">
                ${params.map(p => `
                    <span class="param-preview-tag">
                        ${esc(p)}
                        <span class="param-type">${esc(paramMap[p])}</span>
                    </span>`).join('')}
            </div>`;
    } else {
        paramsEl.innerHTML = '<div style="color:var(--gray-400);">未检测到参数</div>';
    }

    document.getElementById('sqlImportPreview').textContent = converted;
    document.getElementById('btnSqlTest').disabled = false;
    document.getElementById('btnSqlSave').disabled = false;
}

function clearSqlImport() {
    document.getElementById('sqlImportInput').value = '';
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
    showToast('info', '测试查询功能开发中...');
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

    let yaml = `# name: ${name}\n`;
    if (desc) yaml += `# description: ${desc}\n`;
    if (group) yaml += `# group: ${group}\n`;

    if (state._sqlImportPreview && state._sqlImportPreview.length > 0) {
        yaml += '# params:\n';
        state._sqlImportPreview.forEach(p => {
            const paramName = p.replace(/[^\w一-龥]/g, '_').toLowerCase();
            yaml += `#   - name: ${paramName}\n`;
            yaml += `#     label: ${p}\n`;
            yaml += `#     type: text\n`;
            yaml += `#     required: false\n`;
        });
    }

    state._sqlImportYaml = yaml;

    try {
        const res = await fetch('/api/admin/reports/import', {
            method: 'POST',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({
                name: name,
                description: desc,
                group: group,
                sql: yaml + '\n' + state._sqlImportConverted
            })
        });

        if (res.ok) {
            showToast('success', '报表导入成功');
            clearSqlImport();
            hideSqlImport();
            loadReports();
        } else {
            const data = await res.json();
            showToast('error', data.error || '导入失败');
        }
    } catch (e) {
        showToast('error', '导入失败: ' + e.message);
    }
}

// ==================== 用户管理 - 直接加载 ====================
async function loadUsersList() {
    const tbody = document.getElementById('userTableBody');
    if (!tbody) return;
    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--gray-400);">加载中...</td></tr>';

    try {
        const [usersRes, groupsRes] = await Promise.all([
            fetch('/api/admin/users', { credentials: 'include' }),
            fetch('/api/admin/groups', { credentials: 'include' })
        ]);

        if (usersRes.status === 401) { window.location.href = '/login'; return; }

        const users = await usersRes.json();
        const groups = groupsRes.ok ? await groupsRes.json() : [];

        if (users.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--gray-400);">暂无用户</td></tr>';
            return;
        }

        tbody.innerHTML = users.map(user => {
            const groupsHtml = (user.groups && user.groups.length > 0)
                ? user.groups.map(g => `<span class="tag">${esc(g)}</span>`).join('')
                : '<span style="color:var(--gray-400);">全部</span>';

            const groupsJson = JSON.stringify(user.groups || []).replace(/"/g, '&quot;');

            return `<tr>
                <td><strong>${esc(user.username)}</strong></td>
                <td>${esc(user.name || '-')}</td>
                <td><span class="badge ${user.role === 'ADMIN' ? 'badge-primary' : 'badge-default'}">${user.role === 'ADMIN' ? '管理员' : '普通用户'}</span></td>
                <td>${groupsHtml}</td>
                <td>
                    <button class="btn-sm" onclick='editUserGroups("${esc(user.username)}", ${groupsJson})' title="编辑权限">
                        <i class="ri-shield-user-line"></i> 权限
                    </button>
                    <button class="btn-sm" onclick='showResetPwd("${esc(user.username)}")' title="重置密码">
                        <i class="ri-key-line"></i> 密码
                    </button>
                    <button class="btn-sm" onclick='deleteUser("${esc(user.username)}")' title="删除" style="color:var(--danger);">
                        <i class="ri-delete-bin-line"></i>
                    </button>
                </td>
            </tr>`;
        }).join('');
    } catch (e) {
        console.error('加载用户列表失败:', e);
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--danger);">加载失败</td></tr>';
    }
}

// ==================== 分组管理 - 直接加载 ====================
async function loadGroupsList() {
    const tbody = document.getElementById('groupTableBody');
    if (!tbody) return;
    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--gray-400);">加载中...</td></tr>';

    try {
        const res = await fetch('/api/admin/groups', { credentials: 'include' });
        if (res.status === 401) { window.location.href = '/login'; return; }
        const groups = await res.json();

        if (groups.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--gray-400);">暂无分组</td></tr>';
            return;
        }

        // 加载报表分组配置以获取每个分组的报表数
        let assignments = {};
        try {
            const configRes = await fetch('/api/admin/groups/config', { credentials: 'include' });
            const config = await configRes.json();
            assignments = config.assignments || {};
        } catch (_) {}

        // 统计每个分组的报表数
        const reportCounts = {};
        Object.values(assignments).forEach(gid => {
            reportCounts[gid] = (reportCounts[gid] || 0) + 1;
        });

        tbody.innerHTML = groups.map(group => {
            const count = reportCounts[group.id] || 0;
            return `<tr>
                <td><code>${esc(group.id)}</code></td>
                <td><strong>${esc(group.name)}</strong></td>
                <td>${group.sort || 1}</td>
                <td><span class="tag">${count}</span></td>
                <td>
                    <div style="display:flex;gap:4px;flex-wrap:wrap;">
                        <button class="btn-sm" onclick='viewGroupReports("${esc(group.id)}", "${esc(group.name)}")' title="查看报表">
                            <i class="ri-file-list-line"></i>
                        </button>
                        <button class="btn-sm" onclick='editGroup("${esc(group.id)}", "${esc(group.name)}", ${group.sort || 1})' title="编辑">
                            <i class="ri-edit-line"></i>
                        </button>
                        <button class="btn-sm" onclick='deleteGroup("${esc(group.id)}")' title="删除" style="color:var(--danger);">
                            <i class="ri-delete-bin-line"></i>
                        </button>
                    </div>
                </td>
            </tr>`;
        }).join('');
    } catch (e) {
        console.error('加载分组列表失败:', e);
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--danger);">加载失败</td></tr>';
    }
}

// ==================== 系统日志 - 直接加载 ====================
async function loadLogsDirect() {
    const logContent = document.getElementById('logContent');
    const logStats = document.getElementById('logStats');
    if (!logContent) return;

    logContent.innerHTML = '<div style="text-align:center;padding:40px;color:var(--gray-400);">加载中...</div>';

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
            logContent.innerHTML = '<div style="text-align:center;padding:40px;color:var(--gray-400);">暂无日志</div>';
        }
    } catch (e) {
        console.error('加载日志失败:', e);
        logContent.innerHTML = '<div style="text-align:center;padding:40px;color:var(--danger);">加载失败</div>';
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
        const res = await fetch('/api/admin/groups', { credentials: 'include' });
        const groups = await res.json();

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
                <p style="color:var(--gray-500);font-size:13px;margin-top:4px;">勾选该用户可以查看的报表分组</p>
            </div>
            <div style="max-height:300px;overflow-y:auto;border:1px solid var(--gray-200);border-radius:var(--radius);padding:8px;">
                ${checkboxes || '<p style="color:var(--gray-400);text-align:center;padding:20px;">暂无分组</p>'}
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
                showToast('success', '权限更新成功');
                loadUsersList();
            } else {
                const data = await res.json();
                showToast('error', data.error || '更新失败');
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
        </div>
        <div class="form-group">
            <label>新密码</label>
            <input type="password" id="resetPwdNew" placeholder="至少6位" class="form-control">
        </div>
    `;

    showModal('重置密码', html, async () => {
        const newPassword = document.getElementById('resetPwdNew').value;
        const res = await fetch(`/api/admin/users/${encodeURIComponent(username)}/password`, {
            method: 'PUT',
            credentials: 'include',
            headers: csrfHeaders(),
            body: JSON.stringify({ password: newPassword })
        });
        if (res.ok) {
            showToast('success', '密码重置成功');
        } else {
            const data = await res.json();
            showToast('error', data.error || '重置失败');
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
                <input type="number" id="editGroupSort" value="${currentSort}" min="1" class="form-control">
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

// ==================== 新增用户弹窗 ====================
async function showAddUserModal() {
    try {
        const res = await fetch('/api/admin/groups', { credentials: 'include' });
        const groups = await res.json();

        const checkboxes = groups.map(g => `
            <label style="display:flex;align-items:center;gap:8px;padding:8px;cursor:pointer;">
                <input type="checkbox" name="newUserGroups" value="${esc(g.id)}">
                <span>${esc(g.name)} (${esc(g.id)})</span>
            </label>
        `).join('');

        const html = `
            <div style="display:flex;flex-direction:column;gap:16px;">
                <div class="form-group">
                    <label>工号</label>
                    <input type="text" id="newUserUsername" readonly class="form-control" style="background:var(--gray-100);cursor:not-allowed;">
                </div>
                <div class="form-group">
                    <label>姓名</label>
                    <input type="text" id="newUserName" placeholder="输入真实姓名" class="form-control">
                </div>

                <div class="form-group">
                    <label>角色</label>
                    <select id="newUserRole" class="form-control">
                        <option value="USER">普通用户</option>
                        <option value="ADMIN">管理员</option>
                    </select>
                </div>
                <div class="form-group">
                    <label>可查看的报表分组</label>
                    <p style="color:var(--gray-500);font-size:12px;margin-bottom:8px;">不勾选则可查看所有分组</p>
                    <div style="max-height:200px;overflow-y:auto;border:1px solid var(--gray-200);border-radius:var(--radius);padding:8px;">
                        ${checkboxes || '<p style="color:var(--gray-400);text-align:center;">暂无分组</p>'}
                    </div>
                </div>
            </div>
        `;

        // 获取下一个可用工号
        try {
            const usersRes = await fetch('/api/admin/users', { credentials: 'include' });
            if (usersRes.ok) {
                const users = await usersRes.json();
                var nextId = getNextUserId(users);
                setTimeout(function() {
                    var el = document.getElementById('newUserUsername');
                    if (el) el.value = nextId;
                }, 100);
            }
        } catch(e) {}

        showModal('新增用户', html, async () => {
            const username = document.getElementById('newUserUsername').value.trim();
            const name = document.getElementById('newUserName').value.trim();
            const role = document.getElementById('newUserRole').value;
            if (!username || !/^\d{4}$/.test(username)) { showToast('error', '工号无效，请刷新重试'); return; }
            if (!name) { showToast('error', '请填写姓名'); return; }
            const groups = Array.from(document.querySelectorAll('input[name="newUserGroups"]:checked')).map(el => el.value);

            const res = await fetch('/api/admin/users', {
                method: 'POST',
                credentials: 'include',
                headers: csrfHeaders(),
                body: JSON.stringify({ username, name, role, groups })
            });

            if (res.ok) {
                if (groups.length > 0) {
                    try {
                        await fetch('/api/admin/users/' + encodeURIComponent(username) + '/groups', {
                            method: 'PUT', credentials: 'include',
                            headers: csrfHeaders(),
                            body: JSON.stringify({ groups: groups })
                        });
                    } catch(e) {}
                }
                showToast('success', '用户创建成功');
                loadUsersList();
            } else {
                const data = await res.json();
                showToast('error', data.error || '创建失败');
            }
        });
    } catch (e) {
        showToast('error', '加载分组失败');
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
                <h3>${title}</h3>
                <button class="modal-close" onclick="this.closest('.modal').remove()"><i class="ri-close-line"></i></button>
            </div>
            <div class="modal-body">${content}</div>
            <div class="modal-footer" style="padding:16px 24px;border-top:1px solid var(--gray-200);display:flex;justify-content:flex-end;gap:12px;">
                <button class="btn-sm" onclick="this.closest('.modal').remove()" style="padding:10px 20px;">取消</button>
                <button class="btn-primary" id="modalConfirmBtn">确认</button>
            </div>
        </div>
    `;

    document.body.appendChild(modal);
    // 关键修复：添加active类使弹窗可见
    requestAnimationFrame(() => modal.classList.add('active'));

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
        const res = await fetch('/api/admin/datasources');
        if (res.ok) {
            const dataSources = await res.json();
            renderDataSourceList(dataSources);
        }
    } catch (e) {
        console.error('加载数据源失败:', e);
        if (container) container.innerHTML = '<div class="empty-state"><i class="ri-error-warning-line"></i><h3>加载失败</h3><p>请刷新重试</p></div>';
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
                    <button class="btn-sm" onclick="testDataSource('${ds.id}')">
                        <i class="ri-link"></i> 测试连接
                    </button>
                    <button class="btn-sm" onclick="editDataSource('${ds.id}')">
                        <i class="ri-edit-line"></i> 编辑
                    </button>
                    ${!isDefault ? `
                        <button class="btn-sm" onclick="setDefaultDataSource('${ds.id}')">
                            <i class="ri-star-line"></i> 设为默认
                        </button>
                        <button class="btn-sm danger" onclick="deleteDataSource('${ds.id}')">
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

    const driverMap = {
        oracle: 'oracle.jdbc.OracleDriver',
        mysql: 'com.mysql.cj.jdbc.Driver',
        postgresql: 'org.postgresql.Driver',
        sqlserver: 'com.microsoft.sqlserver.jdbc.SQLServerDriver'
    };

    const ds = { name, type, url: finalUrl, username, description, active: true, driverClassName: driverMap[type] || driverMap.oracle };
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

    const driverMap = {
        oracle: 'oracle.jdbc.OracleDriver',
        mysql: 'com.mysql.cj.jdbc.Driver',
        postgresql: 'org.postgresql.Driver',
        sqlserver: 'com.microsoft.sqlserver.jdbc.SQLServerDriver'
    };

    const ds = {
        type: type,
        url: url,
        username: username,
        name: document.getElementById("dsName").value.trim(),
        driverClassName: driverMap[type] || driverMap.oracle
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
                    <small>耗时: ${result.elapsed}ms | 驱动: ${result.driver || '-'} | 版本: ${result.version || '-'}</small>
                </div>`;
        } else {
            resultDiv.innerHTML = `
                <div style="color: var(--danger); padding: 12px; background: #fef2f2; border-radius: var(--radius);">
                    <i class="ri-error-warning-line"></i> ${result.message || '连接失败'}
                </div>`;
        }
    } catch (e) {
        resultDiv.innerHTML = `
            <div style="color: var(--danger); padding: 12px; background: #fef2f2; border-radius: var(--radius);">
                <i class="ri-error-warning-line"></i> 测试失败: ${e.message}
            </div>`;
    }
}

async function deleteDataSource(id) {
    if (!confirm('⚠️ 确定要删除此数据源吗？\n\n删除后使用该数据源的报表将无法查询！')) return;

    try {
        const res = await fetch(`/api/admin/datasources/${encodeURIComponent(id)}`, {
            method: 'DELETE',
            headers: csrfHeaders()
        });

        if (res.ok) {
            showToast('success', '数据源已删除');
            loadDataSources();
        } else {
            const data = await res.json();
            showToast('error', data.error || '删除失败');
        }
    } catch (e) {
        showToast('error', '删除失败: ' + e.message);
    }
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
    initTableDragScroll(); // 初始化表格拖拽滚动
    loadUserInfo();
    loadDefaultDataSourceId();

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

    // 默认显示工作台
    navigateTo('dashboard');
    renderRecentReports();

    console.log('HIS报表系统初始化完成');
});

// ==================== 报表管理（管理员）====================
async function loadReportsList() {
    const tbody = document.getElementById('reportManageTableBody');
    if (!tbody) return;
    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:40px;color:var(--gray-400);">加载中...</td></tr>';

    try {
        const [reportsRes, groupsRes] = await Promise.all([
            fetch('/api/reports', { credentials: 'include' }),
            fetch('/api/admin/groups/config', { credentials: 'include' })
        ]);
        if (reportsRes.status === 401) { window.location.href = '/login'; return; }
        const reports = await reportsRes.json();
        const config = await groupsRes.json();
        const assignments = config.assignments || {};
        const hiddenSet = new Set(config.hiddenReports || []);
        const groups = config.groups || [];

        if (reports.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:40px;color:var(--gray-400);">暂无报表</td></tr>';
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
            const group = assignments[report.id] || 'default';
            const groupName = (groups.find(g => g.id === group) || {}).name || group;
            const hidden = hiddenSet.has(report.id);
            const paramCount = report.params ? report.params.length : 0;

            return `<tr data-group="${esc(group)}" data-name="${esc(report.name).toLowerCase()}" data-id="${esc(report.id).toLowerCase()}">
                <td>
                    <div style="display:flex;flex-direction:column;gap:2px;">
                        <strong>${esc(report.name)}</strong>
                        <small style="color:var(--gray-400);">${esc(report.id)}.sql</small>
                    </div>
                </td>
                <td>${esc(report.description || '-')}</td>
                <td><span class="tag">${esc(groupName)}</span></td>
                <td>${paramCount}</td>
                <td>${hidden ? '<span class="tag" style="color:var(--danger);background:#fef2f2;">已隐藏</span>' : '<span class="tag" style="color:var(--success);">可见</span>'}</td>
                <td>
                    <div style="display:flex;gap:4px;flex-wrap:wrap;">
                        <button class="btn-sm" onclick="editReportSql('${esc(report.id)}')" title="编辑SQL">
                            <i class="ri-code-line"></i>
                        </button>
                        <button class="btn-sm" onclick="toggleReportHidden('${esc(report.id)}', ${!hidden})" title="${hidden ? '取消隐藏' : '隐藏'}">
                            <i class="${hidden ? 'ri-eye-line' : 'ri-eye-off-line'}"></i>
                        </button>
                        <button class="btn-sm" onclick="changeReportGroup('${esc(report.id)}', '${esc(group)}')" title="修改分组">
                            <i class="ri-folder-line"></i>
                        </button>
                        <button class="btn-sm" onclick="deleteReport('${esc(report.id)}')" title="删除" style="color:var(--danger);">
                            <i class="ri-delete-bin-line"></i>
                        </button>
                    </div>
                </td>
            </tr>`;
        }).join('');
    } catch (e) {
        console.error('加载报表列表失败:', e);
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:40px;color:var(--danger);">加载失败</td></tr>';
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

async function deleteReport(reportId) {
    if (!confirm(`⚠️ 确定要删除报表 "${reportId}" 吗？\n\n对应的SQL文件也将被删除，此操作不可撤销！`)) return;

    try {
        const res = await fetch(`/api/admin/reports/${encodeURIComponent(reportId)}`, {
            method: 'DELETE',
            headers: csrfHeaders()
        });
        if (res.ok) {
            showToast('success', '报表已删除');
            loadReportsList();
        } else {
            const data = await res.json();
            showToast('error', data.error || '删除失败');
        }
    } catch (e) {
        showToast('error', '删除失败: ' + e.message);
    }
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
                <div style="font-size:12px;color:var(--gray-400);">
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

async function changeReportGroup(reportId, currentGroup) {
    try {
        const res = await fetch('/api/admin/groups', { credentials: 'include' });
        const groups = await res.json();

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
            } else {
                const err = await putRes.json();
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
            showModal(`分组 "${groupName}" 的报表`, '<div style="text-align:center;padding:20px;color:var(--gray-400);">该分组暂无报表</div>', null);
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
                                <button class="btn-sm" onclick="selectReport('${esc(r.id)}'); hideCommonModal();" title="查询此报表">
                                    <i class="ri-search-line"></i>
                                </button>
                                <button class="btn-sm" onclick="changeReportGroup('${esc(r.id)}', '${esc(groupId)}')" title="移动到其他分组">
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
window.loadLogsDirect = loadLogsDirect;
window.refreshLogs = refreshLogs;
window.exportLogs = exportLogs;
window.switchLogTab = switchLogTab;
window.filterLogLines = filterLogLines;
window.navigateTo = navigateTo;
window.doLogout = doLogout;
window.showChangePassword = showChangePassword;
window.refreshReports = refreshReports;
window.doQuery = doQuery;
window.changePageSize = changePageSize;
window.sortTable = sortTable;
window.copyCellContent = copyCellContent;
window.deleteReport = deleteReport;
window.toggleReportHidden = toggleReportHidden;
window.editReportSql = editReportSql;
window.changeReportGroup = changeReportGroup;
window.loadReportsList = loadReportsList;
window.filterReportsList = filterReportsList;
window.viewGroupReports = viewGroupReports;


// ==================== 首次登录修改密码 ====================
async function checkFirstLogin() {
    try {
        const res = await fetch('/api/admin/current-user', { credentials: 'include' });
        if (res.ok) {
            const user = await res.json();
            if (user.mustChangePassword) {
                document.getElementById('firstLoginModal').classList.add('active');
            }
        }
    } catch (e) {}
}

async function submitFirstLoginPwd() {
    const oldPwd = document.getElementById('firstOldPwd').value;
    const newPwd = document.getElementById('firstNewPwd').value;
    const confirmPwd = document.getElementById('firstConfirmPwd').value;
    if (!oldPwd) { showToast('error', '请输入当前密码'); return; }
    if (!newPwd || newPwd.length < 6) { showToast('error', '新密码至少6位'); return; }
    if (newPwd === '666999') { showToast('error', '新密码不能与初始密码相同'); return; }
    if (newPwd !== confirmPwd) { showToast('error', '两次输入的密码不一致'); return; }
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
