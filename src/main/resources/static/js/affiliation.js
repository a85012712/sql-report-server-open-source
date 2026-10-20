/**
 * affiliation.js — 工号归属管理页面
 * ==================================================================
 * 挂载方式：app.js 的 navigateTo('affiliation') 会调用 loadAffiliationPage()。
 *
 * 数据来源（全部真实接口，无 mock）：
 *   GET  /api/admin/affiliations/dictionaries   字典 + 权限点清单（进入页面时拉一次）
 *   GET  /api/admin/affiliations                归属列表
 *   PUT  /api/admin/affiliations/{username}     保存
 *
 * 设计要点
 * ------------------------------------------------------------------
 * 1. 【前端只做预览，服务端才是权威】前端按与服务端完全相同的规则本地派生，
 *    让改动能即时反映在界面上；保存时**只提交源字段**，范围由服务端重新派生。
 *    保存成功后用服务端返回的 derived 覆盖本地状态，保证两边永不漂移。
 * 2. 【全量重渲染 + 焦点/滚动保持】组件不保存业务状态，交互后整块重绘。
 * 3. 【事件委托】监听器只挂一次，重绘不丢事件。
 */
(function () {
    'use strict';

    var Dict = null;          // 字典（communities/departments/roles/positions/permissions）
    // 注意：不要给字典加「只拉一次」的缓存守卫：角色权限会变（见 loadAffiliationPage 的注释）
    var Users = [];           // 归属列表（服务端返回）

    var state = {
        keyword: '', community: '', roleId: '',
        selected: null,
        draft: null,
        dirty: false,
        deptFilter: ''        // 科室 chips 的搜索词
    };

    var root = null, bodyEl = null, toastTimer = null;

    /* ============================================================
     * 一、工具
     * ============================================================ */

    var ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
    function esc(s) {
        if (s === null || s === undefined) return '';
        return String(s).replace(/[&<>"']/g, function (c) { return ESC_MAP[c]; });
    }
    function find(arr, key, val) {
        if (!arr) return null;
        for (var i = 0; i < arr.length; i++) if (arr[i][key] === val) return arr[i];
        return null;
    }
    function commName(code) { var c = find(Dict && Dict.communities, 'code', code); return c ? c.name : (code || ''); }
    function deptName(code) { var d = find(Dict && Dict.departments, 'code', code); return d ? d.name : (code || ''); }
    /**
     * 科室显示名（下拉与勾选项统一用它）。
     *
     * 停用科室必须**标注**而不是隐藏：
     *   - 隐藏会让历史上已挂在该科室的工号「归属科室」凭空消失，更糟；
     *   - 但不标注就会让人不知情地把工号新挂到已停用科室上。
     *   社区下拉早就是这么做的（`c.enabled ? '' : '（停用）'`），科室这里对齐它。
     */
    function deptLabel(d) {
        return d.name + '（' + d.code + '）' + (d.enabled === false ? '（停用）' : '');
    }
    function roleName(id) { var r = find(Dict && Dict.roles, 'id', id); return r ? r.name : (id || ''); }
    function posName(code) { var p = find(Dict && Dict.positions, 'code', code); return p ? p.name : (code || '—'); }
    function permName(code) { var p = find(Dict && Dict.permissions, 'code', code); return p ? p.name : code; }

    function toast(type, msg) {
        if (typeof window.showToast === 'function') { window.showToast(type, msg); return; }
        if (!bodyEl) return;
        var el = root.querySelector('.af-toast');
        if (!el) return;
        el.textContent = msg;
        el.className = 'af-toast show ' + (type === 'error' ? 'err' : (type === 'info' ? 'info' : 'ok'));
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { el.className = 'af-toast'; }, 2800);
    }

    /* ============================================================
     * 二、派生引擎（规则与后端 AffiliationService 保持一致）
     * ============================================================ */

    var SCOPE_LABEL = {
        'ALL': '不限（全部社区 / 科室）',
        'SELF_COMMUNITY': '随职务 · 本社区',
        'SELF_DEPT': '随职务 · 本科室',
        'CUSTOM': '自定义覆盖'
    };

    function derive(d) {
        var dept = d.department ? find(Dict.departments, 'code', d.department) : null;
        var pos = d.position ? find(Dict.positions, 'code', d.position) : null;
        var role = d.roleId ? find(Dict.roles, 'id', d.roleId) : null;

        // 归属社区：由科室的 community 字段决定（不手工维护）
        var comm = (!d.superAdmin && dept) ? dept.community : null;

        // 数据范围：显式覆盖 > 职务规则
        var sc = [], sd = [], rule;
        if (d.superAdmin) {
            rule = 'ALL';
        } else if (d.scopeOverride) {
            rule = 'CUSTOM';
            sc = (d.communities || []).slice();
            sd = (d.departments || []).slice();
        } else {
            rule = pos ? pos.scopeRule : 'SELF_DEPT';
            if (rule === 'ALL') {
                // 不限：两个列表都留空
            } else if (rule === 'SELF_COMMUNITY') {
                if (comm) sc.push(comm);
            } else {
                if (comm) sc.push(comm);
                if (d.department) sd.push(d.department);
            }
        }

        var issues = [];
        if (d.superAdmin) {
            issues.push({ level: 'warn', text: '内置超级管理员：账号由环境变量 ADMIN_USERNAME 指定，不参与归属分配，归属不可变更' });
        } else {
            if (!d.department) {
                issues.push({ level: 'warn', text: '未设置归属科室，数据范围将为空' });
            } else if (!dept) {
                issues.push({ level: 'error', text: '科室编码在字典中不存在：' + d.department });
            } else if (!dept.community) {
                issues.push({ level: 'warn', text: '科室「' + dept.name + '」未挂社区（行政/共享岗），社区范围将不受限' });
            }
            if (d.scopeOverride && !sc.length && !sd.length) {
                issues.push({ level: 'warn', text: '已开启自定义覆盖但未勾选任何社区/科室，将表示「不限」' });
            }
            if (!role) {
                issues.push({ level: 'error', text: '角色不存在：' + d.roleId });
            } else if (d.roleId === 'role_screen') {
                // 与服务端保持一致：大屏查看是可分配角色，但要让管理员知道它的后果
                issues.push({ level: 'warn', text: '「大屏查看」是通道型角色：该工号登录后将直达数据大屏，看不到其他菜单' });
            }
        }
        var hasError = issues.some(function (i) { return i.level === 'error'; });

        return {
            department: dept, position: pos, community: comm,
            scope: { communities: sc, departments: sd, rule: rule },
            permissions: role ? role.permissions.slice() : [],
            issues: issues, hasError: hasError
        };
    }

    /* ============================================================
     * 三、渲染
     * ============================================================ */

    function renderToolbar() {
        var commOpts = ['<option value="">全部社区</option>'];
        Dict.communities.forEach(function (c) {
            commOpts.push('<option value="' + esc(c.code) + '"' + (state.community === c.code ? ' selected' : '') +
                '>' + esc(c.name) + (c.enabled ? '' : '（停用）') + '</option>');
        });
        var roleOpts = ['<option value="">全部角色</option>'];
        Dict.roles.forEach(function (r) {
            roleOpts.push('<option value="' + esc(r.id) + '"' + (state.roleId === r.id ? ' selected' : '') +
                '>' + esc(r.name) + '</option>');
        });
        return '' +
            '<div class="af-toolbar">' +
                '<div class="af-search">' +
                    '<input class="form-control" type="text" placeholder="搜索工号 / 姓名 / 科室" ' +
                        'data-field="keyword" data-scope="filter" value="' + esc(state.keyword) + '">' +
                '</div>' +
                '<select class="form-control" data-field="community" data-scope="filter">' + commOpts.join('') + '</select>' +
                '<select class="form-control" data-field="roleId" data-scope="filter">' + roleOpts.join('') + '</select>' +
                '<button class="btn btn-default" data-act="clear-filters">重置</button>' +
                '<div class="af-stats">共 ' + Users.length + ' 个工号</div>' +
            '</div>';
    }

    function visibleUsers() {
        var kw = (state.keyword || '').trim().toLowerCase();
        return Users.filter(function (u) {
            if (kw) {
                var hay = ((u.username || '') + ' ' + (u.name || '') + ' ' +
                           (u.departmentName || '') + ' ' + (u.communityName || '')).toLowerCase();
                if (hay.indexOf(kw) === -1) return false;
            }
            if (state.community && u.community !== state.community) return false;
            if (state.roleId && u.roleId !== state.roleId) return false;
            return true;
        });
    }

    function renderList() {
        var list = visibleUsers();
        var rows = list.map(function (u) {
            var active = u.username === state.selected;
            return '' +
                '<tr class="' + (active ? 'active' : '') + '" data-act="select" data-username="' + esc(u.username) + '">' +
                    '<td class="af-mono">' + esc(u.username) + '</td>' +
                    '<td>' + esc(u.name) +
                        (u.superAdmin ? ' <span class="af-tag is-warn">内置</span>' : '') + '</td>' +
                    '<td>' + (u.department ? esc(u.departmentName || u.department)
                        : '<span class="af-tag is-plain">未设置</span>') + '</td>' +
                    '<td>' + (u.community ? '<span class="af-tag">' + esc(u.communityName || u.community) + '</span>'
                        : '<span class="af-tag is-plain">不限</span>') + '</td>' +
                    '<td>' + esc(u.positionName || '—') + '</td>' +
                    '<td>' + esc(u.roleName || u.roleId) + '</td>' +
                '</tr>';
        });
        var body = rows.length
            ? '<table class="af-table"><thead><tr><th>工号</th><th>姓名</th><th>归属科室</th>' +
              '<th>社区</th><th>职务</th><th>角色</th></tr></thead><tbody>' + rows.join('') + '</tbody></table>'
            : '<div class="af-empty">没有匹配的工号<br><span style="font-size:12px">试着清空搜索词或筛选条件</span></div>';

        return '' +
            '<div class="af-card">' +
                '<div class="af-card-head"><h3>工号列表</h3>' +
                    '<span class="af-sub">' + list.length + ' / ' + Users.length + '</span>' +
                '</div>' +
                '<div class="af-card-body">' + body + '</div>' +
            '</div>';
    }

    function renderDeptOptions(selected) {
        var groups = {}, noComm = [];
        Dict.departments.forEach(function (d) {
            if (!d.community) { noComm.push(d); return; }
            (groups[d.community] = groups[d.community] || []).push(d);
        });
        var out = ['<option value=""' + (!selected ? ' selected' : '') + '>— 未设置 —</option>'];
        Object.keys(groups).sort().forEach(function (c) {
            out.push('<optgroup label="' + esc(commName(c)) + '">');
            groups[c].forEach(function (d) {
                out.push('<option value="' + esc(d.code) + '"' + (d.code === selected ? ' selected' : '') +
                    '>' + esc(deptLabel(d)) + '</option>');
            });
            out.push('</optgroup>');
        });
        if (noComm.length) {
            out.push('<optgroup label="未挂社区（行政 / 共享岗）">');
            noComm.forEach(function (d) {
                out.push('<option value="' + esc(d.code) + '"' + (d.code === selected ? ' selected' : '') +
                    '>' + esc(d.name) + '（' + esc(d.code) + '）</option>');
            });
            out.push('</optgroup>');
        }
        return out.join('');
    }

    function renderPermissions(draft, derived) {
        var own = derived.permissions;
        var before = null;
        var u = find(Users, 'username', draft.username);
        if (u && u.roleId !== draft.roleId && Dict) {
            var r = find(Dict.roles, 'id', u.roleId);
            before = r ? r.permissions : [];
        }
        var chips = Dict.permissions.map(function (p) {
            var on = own.indexOf(p.code) !== -1;
            var changed = before && (before.indexOf(p.code) !== -1) !== on;
            return '<span class="af-perm ' + (on ? 'on' : 'off') + (changed ? ' is-changed' : '') + '">' +
                (on ? '✓' : '✕') + ' ' + esc(p.name) + '</span>';
        });
        return '' +
            '<div class="af-section">' +
                '<div class="af-section-head">权限点' +
                    '<span class="af-hint">由角色「' + esc(roleName(draft.roleId)) + '」决定 · ' +
                        own.length + ' / ' + Dict.permissions.length + ' 项' +
                        (before ? ' · 黄框为本次变化' : '') + '</span>' +
                '</div>' +
                '<div class="af-section-body"><div class="af-perm-list">' + chips.join('') + '</div></div>' +
            '</div>';
    }

    function renderScope(draft, derived) {
        var sc = derived.scope;
        var commTags = sc.communities.length
            ? sc.communities.map(function (c) { return '<span class="af-tag is-primary">' + esc(commName(c)) + '</span>'; }).join('')
            : '<span class="af-tag is-success">不限</span>';
        var deptTags = sc.departments.length
            ? sc.departments.map(function (c) { return '<span class="af-tag is-primary">' + esc(deptName(c)) + '</span>'; }).join('')
            : '<span class="af-tag is-success">不限</span>';

        var picker = '';
        if (draft.scopeOverride) {
            var kw = (state.deptFilter || '').toLowerCase();

            var commChips = Dict.communities.map(function (c) {
                var on = (draft.communities || []).indexOf(c.code) !== -1;
                return '<span class="af-chip' + (on ? ' on' : '') + '" data-act="toggle-scope-item" ' +
                    'data-kind="communities" data-code="' + esc(c.code) + '">' + esc(c.name) + '</span>';
            }).join('');

            var groups = {}, noComm = [];
            Dict.departments.forEach(function (d) {
                if (!d.community) { noComm.push(d); return; }
                (groups[d.community] = groups[d.community] || []).push(d);
            });
            function deptChip(d) {
                var on = (draft.departments || []).indexOf(d.code) !== -1;
                return '<span class="af-chip' + (on ? ' on' : '') + '" data-act="toggle-scope-item" ' +
                    'data-kind="departments" data-code="' + esc(d.code) + '">' +
                    esc(deptLabel(d)) + '</span>';
            }
            function match(d) {
                if (!kw) return true;
                return (d.name + ' ' + d.code).toLowerCase().indexOf(kw) !== -1;
            }
            var deptChips = Object.keys(groups).sort().map(function (c) {
                var items = groups[c].filter(match);
                if (!items.length) return '';
                return '<span class="af-chip-group-label">' + esc(commName(c)) + '</span>' + items.map(deptChip).join('');
            }).join('');
            var nc = noComm.filter(match);
            if (nc.length) {
                deptChips += '<span class="af-chip-group-label">未挂社区</span>' + nc.map(deptChip).join('');
            }

            picker = '' +
                '<div class="af-field">' +
                    '<label>可见社区</label>' +
                    '<div class="af-control is-stack"><div class="af-chips">' + commChips + '</div></div>' +
                '</div>' +
                '<div class="af-field">' +
                    '<label>可见科室</label>' +
                    '<div class="af-control is-stack">' +
                        '<input class="form-control" type="text" placeholder="搜索科室（共 ' + Dict.departments.length + ' 个）" ' +
                            'data-field="deptFilter" data-scope="draft" value="' + esc(state.deptFilter) + '">' +
                        '<div class="af-chips">' + deptChips + '</div>' +
                    '</div>' +
                '</div>' +
                '<div class="af-issue warn"><span>ⓘ</span><span>覆盖开启后，上面勾选的内容将直接决定该工号能看哪些' +
                    '社区 / 科室的数据，不再跟随职务规则。留空表示「不限」。</span></div>';
        }

        return '' +
            '<div class="af-section">' +
                '<div class="af-section-head">数据范围' +
                    '<span class="af-hint">决定这个工号能看到哪些社区 / 科室的数据</span></div>' +
                '<div class="af-section-body">' +
                    '<div class="af-scope-grid">' +
                        '<div class="af-scope-key">生效规则</div>' +
                        '<div class="af-scope-val"><span class="af-tag is-primary">' +
                            esc(SCOPE_LABEL[sc.rule] || sc.rule) + '</span></div>' +
                        '<div class="af-scope-key">可见社区</div><div class="af-scope-val">' + commTags + '</div>' +
                        '<div class="af-scope-key">可见科室</div><div class="af-scope-val">' + deptTags + '</div>' +
                    '</div>' +
                    '<label class="af-check">' +
                        '<input type="checkbox" data-act="toggle-scope-enabled"' +
                            (draft.scopeOverride ? ' checked' : '') +
                            (draft.superAdmin ? ' disabled' : '') + '> 自定义覆盖（不随职务规则）' +
                    '</label>' + picker +
                '</div>' +
            '</div>';
    }

    function renderIssues(derived) {
        if (!derived.issues.length) {
            return '<div class="af-issues"><div class="af-issue ok">' +
                '<span>✓</span><span>配置有效，保存后立即生效。</span></div></div>';
        }
        return '<div class="af-issues">' + derived.issues.map(function (i) {
            return '<div class="af-issue ' + i.level + '"><span>' +
                (i.level === 'error' ? '✕' : '⚠') + '</span><span>' + esc(i.text) + '</span></div>';
        }).join('') + '</div>';
    }

    function renderEditor() {
        var draft = state.draft;
        if (!draft) {
            return '' +
                '<div class="af-card">' +
                    '<div class="af-card-head"><h3>归属编辑</h3></div>' +
                    '<div class="af-card-body"><div class="af-placeholder">' +
                        '<div class="af-ph-icon">👤</div>' +
                        '<div class="af-ph-title">请从左侧选择一个工号</div>' +
                        '<div class="af-ph-desc">选中后可调整该工号的科室、职务、角色与数据范围。<br>' +
                        '归属社区由科室自动推导，权限点随角色自动变化。</div>' +
                    '</div></div>' +
                '</div>';
        }

        var derived = derive(draft);
        var u = find(Users, 'username', draft.username) || {};

        var commControl;
        if (draft.communityDerived) {
            var dept = find(Dict.departments, 'code', draft.department);
            commControl = dept
                ? '<span class="af-readonly is-derived">' + esc(dept.community ? commName(dept.community) : '该科室未挂社区') +
                  '<span class="af-note">来自「' + esc(dept.name) + '」</span></span>'
                : '<span class="af-readonly"><span class="af-note">先选择归属科室</span></span>';
        } else {
            var opts = ['<option value="">— 请选择 —</option>'];
            Dict.communities.forEach(function (c) {
                opts.push('<option value="' + esc(c.code) + '"' +
                    (draft.communityOverride === c.code ? ' selected' : '') + '>' + esc(c.name) + '</option>');
            });
            commControl = '<select class="form-control" data-field="communityOverride" data-scope="draft">' +
                opts.join('') + '</select>';
        }

        /* 角色下拉展示**全部**角色（含大屏查看），与「角色管理」页保持一致。
         * 大屏查看是合法的可分配角色（该工号登录后直达大屏），只是语义特殊，
         * 用 optgroup 单独标注而不是让它凭空消失，否则会出现
         * 「角色管理里有、归属管理里没有」的不一致。 */
        var roleOne = function (r) {
            return '<option value="' + esc(r.id) + '"' + (draft.roleId === r.id ? ' selected' : '') +
                '>' + esc(r.name) + (r.builtin ? '' : ' · 自定义') + '</option>';
        };
        var normalRoles = Dict.roles.filter(function (r) { return r.id !== 'role_screen'; });
        var screenRoles = Dict.roles.filter(function (r) { return r.id === 'role_screen'; });
        var roleOpts = normalRoles.map(roleOne).join('');
        if (screenRoles.length) {
            roleOpts += '<optgroup label="大屏通道（该工号登录后直达大屏）">' +
                screenRoles.map(roleOne).join('') + '</optgroup>';
        }

        var posOpts = ['<option value="">— 未设置 —</option>'];
        Dict.positions.forEach(function (p) {
            posOpts.push('<option value="' + esc(p.code) + '"' + (draft.position === p.code ? ' selected' : '') +
                '>' + esc(p.name) + '</option>');
        });
        var pos = draft.position ? find(Dict.positions, 'code', draft.position) : null;
        var disabled = draft.superAdmin ? ' disabled' : '';

        return '' +
            '<div class="af-card">' +
                '<div class="af-card-head"><h3>归属编辑</h3>' +
                    '<span class="af-sub">' + esc(u.name || '') + ' · ' + esc(draft.username) + '</span>' +
                    (draft.superAdmin ? '<span class="af-tag is-warn">内置超管</span>' : '') +
                '</div>' +
                '<div class="af-card-body"><div class="af-editor">' +
                    '<div class="af-section">' +
                        '<div class="af-section-head">归属信息' +
                            '<span class="af-hint">科室 → 社区 → 数据范围，逐级推导</span></div>' +
                        '<div class="af-section-body">' +
                            '<div class="af-field"><label>归属科室</label><div class="af-control">' +
                                '<select class="form-control" data-field="department" data-scope="draft"' + disabled + '>' +
                                    renderDeptOptions(draft.department) + '</select></div></div>' +
                            '<div class="af-field"><label>归属社区</label>' +
                                '<div class="af-control is-stack">' +
                                    '<label class="af-check"><input type="checkbox" data-act="toggle-derived"' +
                                        (draft.communityDerived ? ' checked' : '') + disabled + '> 由科室自动推导</label>' +
                                    '<div>' + commControl + '</div>' +
                                '</div></div>' +
                            '<div class="af-field"><label>职务</label><div class="af-control">' +
                                '<select class="form-control" data-field="position" data-scope="draft"' + disabled + '>' +
                                    posOpts.join('') + '</select>' +
                                '<span class="af-note">' + (pos ? '默认角色：' + esc(roleName(pos.defaultRoleId)) +
                                    ' · 范围：' + esc(SCOPE_LABEL[pos.scopeRule] || pos.scopeRule) : '') + '</span>' +
                            '</div></div>' +
                            '<div class="af-field"><label>角色</label><div class="af-control">' +
                                '<select class="form-control" data-field="roleId" data-scope="draft"' + disabled + '>' +
                                    roleOpts + '</select>' +
                                '<span class="af-note">' + (draft.roleTouched ? '已手工指定' : '跟随职务自动带出') + '</span>' +
                            '</div></div>' +
                        '</div>' +
                    '</div>' +
                    renderPermissions(draft, derived) +
                    renderScope(draft, derived) +
                    renderIssues(derived) +
                '</div></div>' +
                '<div class="af-actions">' +
                    '<span class="af-dirty">' + (state.dirty ? '● 有未保存的修改' : '') + '</span>' +
                    '<span class="af-spacer"></span>' +
                    '<button class="btn btn-default" data-act="reset">放弃修改</button>' +
                    '<button class="btn btn-primary" data-act="save"' +
                        (draft.superAdmin ? ' disabled' : '') + '>保存归属</button>' +
                '</div>' +
            '</div>';
    }

    /* ============================================================
     * 四、渲染主流程（焦点 + 滚动保持）
     * ============================================================ */

    var SCROLL_SELECTORS = ['.af-card-body', '.af-chips'];

    function captureFocus() {
        var a = document.activeElement;
        if (!a || !bodyEl || !bodyEl.contains(a)) return null;
        var field = a.getAttribute && a.getAttribute('data-field');
        if (!field) return null;
        var scope = a.getAttribute && a.getAttribute('data-scope');
        var sel = null;
        try { if (a.tagName === 'INPUT' && a.type === 'text') sel = [a.selectionStart, a.selectionEnd]; } catch (e) {}
        return { field: field, scope: scope, sel: sel };
    }
    function restoreFocus(snap) {
        if (!snap || !bodyEl) return;
        var q = '[data-field="' + snap.field + '"]' + (snap.scope ? '[data-scope="' + snap.scope + '"]' : '');
        var el = bodyEl.querySelector(q);
        if (!el) return;
        el.focus();
        if (snap.sel && el.setSelectionRange) { try { el.setSelectionRange(snap.sel[0], snap.sel[1]); } catch (e) {} }
    }
    function captureScroll() {
        var map = [];
        SCROLL_SELECTORS.forEach(function (sel) {
            var els = bodyEl.querySelectorAll(sel);
            for (var i = 0; i < els.length; i++) if (els[i].scrollTop) map.push([sel, i, els[i].scrollTop]);
        });
        return map;
    }
    function restoreScroll(map) {
        map.forEach(function (it) {
            var el = bodyEl.querySelectorAll(it[0])[it[1]];
            if (el) el.scrollTop = it[2];
        });
    }

    function render() {
        if (!bodyEl) return;
        if (!Dict) {
            bodyEl.innerHTML = '<div class="af-empty">正在加载字典…</div>';
            return;
        }
        var snap = captureFocus();
        var scroll = captureScroll();
        bodyEl.innerHTML = renderToolbar() +
            '<div class="af-main">' + renderList() + renderEditor() + '</div>';
        restoreFocus(snap);
        restoreScroll(scroll);
    }

    /* ============================================================
     * 五、动作
     * ============================================================ */

    function selectUser(username) {
        var u = find(Users, 'username', username);
        if (!u) return;
        state.selected = username;
        state.dirty = false;
        state.deptFilter = '';
        state.draft = {
            username: u.username,
            superAdmin: !!u.superAdmin,
            department: u.department || null,
            communityDerived: !u.scopeOverride || u.communityDerived !== false,
            communityOverride: null,
            position: u.position || null,
            roleId: u.roleId,
            roleTouched: false,
            scopeOverride: !!u.scopeOverride,
            communities: (u.communities || []).slice(),
            departments: (u.departments || []).slice()
        };
        // 关闭自动推导时才有手工社区值；这里用派生社区做初值
        if (u.scopeOverride && !u.community) {
            state.draft.communityDerived = false;
        } else {
            state.draft.communityDerived = true;
        }
        render();
    }

    function patchDraft(patch) {
        if (!state.draft) return;
        var d = Object.assign({}, state.draft, patch);
        if (Object.prototype.hasOwnProperty.call(patch, 'position') && !state.draft.roleTouched) {
            var p = find(Dict.positions, 'code', d.position);
            if (p) d.roleId = p.defaultRoleId;
        }
        if (Object.prototype.hasOwnProperty.call(patch, 'roleId')) d.roleTouched = true;
        state.draft = d;
        state.dirty = true;
        render();
    }

    function save() {
        var d = state.draft;
        if (!d) return;
        var payload = {
            department: d.department || null,
            position: d.position || null,
            roleId: d.roleId || null,
            scopeOverride: !!d.scopeOverride,
            communities: d.scopeOverride ? (d.communities || []) : [],
            departments: d.scopeOverride ? (d.departments || []) : []
        };
        var btn = root.querySelector('[data-act="save"]');
        if (btn) { btn.disabled = true; btn.textContent = '保存中…'; }

        apiFetch('/api/admin/affiliations/' + encodeURIComponent(d.username), {
            method: 'PUT',
            body: JSON.stringify(payload)
        }).then(function (res) {
            return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        }).then(function (r) {
            if (!r.ok || !r.body || r.body.ok !== true) {
                throw new Error((r.body && r.body.error) || '保存失败');
            }
            toast('success', '归属已保存，权限与数据范围立即生效');
            return reloadList().then(function () {
                // 用服务端返回的派生结果刷新本地状态，保证前后端永不漂移
                if (r.body.derived) {
                    var u = find(Users, 'username', d.username);
                    if (u) Object.assign(u, { communities: r.body.derived.communities, departments: r.body.derived.departments });
                }
                state.dirty = false;
                selectUser(d.username);
            });
        }).catch(function (e) {
            toast('error', e.message || '保存失败');
            if (btn) { btn.disabled = false; btn.textContent = '保存归属'; }
        });
    }

    function reloadList() {
        return apiFetch('/api/admin/affiliations').then(function (res) {
            if (!res.ok) throw new Error('加载归属列表失败');
            return res.json();
        }).then(function (list) {
            Users = list || [];
            render();
        });
    }

    /* ============================================================
     * 六、事件
     * ============================================================ */

    function bindEvents() {
        root.addEventListener('click', function (e) {
            var el = e.target.closest ? e.target.closest('[data-act]') : null;
            if (!el || !root.contains(el)) return;
            var act = el.getAttribute('data-act');

            if (act === 'select') { selectUser(el.getAttribute('data-username')); return; }
            if (act === 'clear-filters') {
                state.keyword = ''; state.community = ''; state.roleId = '';
                render(); return;
            }
            if (act === 'reset') {
                if (!state.dirty) { toast('info', '没有需要放弃的修改'); return; }
                selectUser(state.selected);
                toast('info', '已放弃未保存的修改');
                return;
            }
            if (act === 'save') { save(); return; }
            if (act === 'toggle-scope-item') {
                var kind = el.getAttribute('data-kind'), code = el.getAttribute('data-code');
                var arr = (state.draft[kind] || []).slice();
                var i = arr.indexOf(code);
                if (i === -1) arr.push(code); else arr.splice(i, 1);
                var patch = { scopeOverride: true };
                patch[kind] = arr;
                patchDraft(patch);
                return;
            }
        });

        root.addEventListener('change', function (e) {
            var el = e.target;
            if (el.type === 'checkbox') {
                var act = el.getAttribute('data-act');
                if (act === 'toggle-derived') {
                    var d = Object.assign({}, state.draft, { communityDerived: el.checked });
                    if (!el.checked && !d.communityOverride) {
                        d.communityOverride = derive(state.draft).community;
                    }
                    state.draft = d; state.dirty = true; render();
                    return;
                }
                if (act === 'toggle-scope-enabled') { patchDraft({ scopeOverride: el.checked }); return; }
                return;
            }
            var field = el.getAttribute('data-field');
            var scope = el.getAttribute('data-scope');
            if (!field) return;
            if (scope === 'filter') {
                state[field] = el.value;
                render();
                return;
            }
            if (scope === 'draft') {
                if (field === 'deptFilter') { state.deptFilter = el.value; render(); return; }
                if (field === 'communityOverride') {
                    state.draft = Object.assign({}, state.draft, { communityOverride: el.value || null });
                    state.dirty = true; render(); return;
                }
                var p = {}; p[field] = el.value || null;
                patchDraft(p);
            }
        });

        root.addEventListener('input', function (e) {
            var el = e.target;
            if (el.tagName !== 'INPUT' || el.type !== 'text') return;
            var field = el.getAttribute('data-field');
            if (field === 'keyword') { state.keyword = el.value; render(); return; }
            if (field === 'deptFilter') { state.deptFilter = el.value; render(); }
        });
    }

    /* ============================================================
     * 七、入口
     * ============================================================ */

    window.loadAffiliationPage = function () {
        root = document.getElementById('af-root');
        if (!root) return;
        if (!bodyEl) {
            root.classList.add('af-root');
            root.innerHTML = '<div class="af-body"></div>';
            bodyEl = root.querySelector('.af-body');
            bindEvents();
        }
        /* 每次进入都重新拉字典，不要只拉一次。
         *
         *   原先这里写的是 `if (inited) { render(); return; }`，字典只在**首次进入**时取，
         *   之后再进这个页面都复用缓存。于是「在角色管理里改掉某角色的权限 → 回归属管理」
         *   仍然显示**旧权限**（实测：科主任去掉 personnel.manage 后，权限点区依旧 ✓ 人员管理）。
         *   同一份角色权限数据，角色管理页实时、归属管理页永久缓存，又是一处「两套判据」。
         *
         *   角色权限是**会变的**（管理员随时可在角色管理里改），所以这里没有「可以缓存」的余地：
         *   每次进入都以服务端为准。代价是每进一次多一个 GET（`/api/admin/*` 限流 5 次/秒，
         *   本页进一次只多这一个请求，可接受）。
         */
        render();   // 先用现有数据画一版（首次进入显示「正在加载字典…」），避免白屏
        apiFetch('/api/admin/affiliations/dictionaries').then(function (res) {
            if (!res.ok) throw new Error('加载字典失败');
            return res.json();
        }).then(function (dict) {
            Dict = dict;
            // render() 内部用 Dict 重算派生结果与权限点，所以这里 reloadList 后
            // 已选工号的权限点区会立刻变成**新**角色的权限
            return reloadList();
        }).catch(function (e) {
            bodyEl.innerHTML = '<div class="af-empty">加载失败：' + esc(e.message) + '</div>';
            toast('error', e.message || '加载失败');
        });
    };
})();
