/* ============================================================
   可视化平台·数据大屏 — 前端逻辑
   60秒轮询 /api/screen/data（读后端缓存，不直接打库）。
   取数失败/无数据时**保留上一次成功画面并显式标注「数据不可用」**，
     绝不回落演示数据，这块屏挂在医院墙上，编出来的数字会被当成真数。
   ============================================================ */
'use strict';

// ==================== 工具 ====================
function getCsrfToken() {
    var m = document.querySelector('meta[name="_csrf"]');
    return m ? m.getAttribute('content') : '';
}
function getCsrfHeader() {
    var m = document.querySelector('meta[name="_csrf_header"]');
    return m ? m.getAttribute('content') : 'X-CSRF-TOKEN';
}

function doLogout() {
    var csrfMeta = document.querySelector('meta[name="_csrf"]');
    var csrfInput = document.getElementById('logoutCsrf');
    if (csrfMeta && csrfInput) csrfInput.value = csrfMeta.getAttribute('content');
    var f = document.getElementById('logoutForm');
    if (f) f.submit();
}

function fmtWan(v) { // 元 -> 万元（两位小数）
    if (v == null || isNaN(v)) return '--';
    return (Number(v) / 10000).toFixed(2);
}
function fmtPct(v) {
    if (v == null || isNaN(v)) return '--';
    return Number(v).toFixed(2);
}
function fmtDelta(cur, prev, unitPct) {
    if (cur == null || prev == null || isNaN(cur) || isNaN(prev) || Number(prev) === 0) return { text: '--', cls: 'flat' };
    var d = ((Number(cur) - Number(prev)) / Number(prev)) * 100;
    var sign = d > 0 ? '+' : '';
    var cls = d > 0 ? 'up' : (d < 0 ? 'down' : 'flat');
    return { text: sign + d.toFixed(1) + '%', cls: cls };
}
// 数字滚动动画
function animateNum(el, target, duration) {
    if (el == null || target == null || isNaN(Number(target))) { if (el) el.textContent = '--'; return; }
    var start = 0, from = parseFloat(String(el.dataset.v || '0')) || 0;
    var to = Number(target);
    el.dataset.v = to;
    var t0 = performance.now();
    duration = duration || 900;
    function step(t) {
        var p = Math.min((t - t0) / duration, 1);
        var e = 1 - Math.pow(1 - p, 3); // easeOutCubic
        var v = from + (to - from) * e;
        el.textContent = Math.round(v).toLocaleString('zh-CN');
        if (p < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
}

// ==================== 等比缩放（1920x1080 设计稿） ====================
function fitScale() {
    var wrap = document.getElementById('screen-wrap');
    var s = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
    wrap.style.transform = 'translate(-50%, -50%) scale(' + s + ')';
}
window.addEventListener('resize', function () {
    fitScale();
    // 图表随容器重绘
    for (var k in charts) if (charts[k]) charts[k].resize();
});

// ==================== 时钟 ====================
function tickClock() {
    var el = document.getElementById('headerClock');
    if (!el) return;
    var d = new Date();
    var week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()];
    function p(n) { return n < 10 ? '0' + n : '' + n; }
    el.innerHTML = d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
        + ' 星期' + week + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds()) + '';
}
setInterval(tickClock, 1000);

// ==================== ECharts 公共配置 ====================
var charts = {};

var AXIS_STYLE = {
    axisLine: { lineStyle: { color: 'rgba(90,140,200,0.45)' } },
    axisLabel: { color: '#6f9cc9', fontSize: 12 },
    axisTick: { show: false },
    splitLine: { lineStyle: { color: 'rgba(90,140,200,0.12)' } }
};
var TOOLTIP_STYLE = {
    backgroundColor: 'rgba(4,18,48,0.92)',
    borderColor: 'rgba(0,212,255,0.4)',
    borderWidth: 1,
    textStyle: { color: '#dcecff', fontSize: 13 },
    axisPointer: { lineStyle: { color: 'rgba(0,212,255,0.35)' } }
};

function initCharts() {
    charts.outpatientTrend = echarts.init(document.getElementById('chartOutpatientTrend'));
    charts.revenueTrend = echarts.init(document.getElementById('chartRevenueTrend'));
    charts.hourly = echarts.init(document.getElementById('chartHourly'));
    charts.community = echarts.init(document.getElementById('chartCommunity'));
    charts.category = echarts.init(document.getElementById('chartCategory'));
    charts.admission = echarts.init(document.getElementById('chartAdmission'));
}

// ---- 近30天门诊量趋势：渐变面积折线 ----
function renderOutpatientTrend(days, values) {
    charts.outpatientTrend.setOption({
        tooltip: Object.assign({ trigger: 'axis' }, TOOLTIP_STYLE),
        grid: { left: 52, right: 26, top: 30, bottom: 30 },
        xAxis: Object.assign({ type: 'category', data: days, boundaryGap: false }, AXIS_STYLE),
        yAxis: Object.assign({ type: 'value', name: '人次' }, AXIS_STYLE),
        series: [{
            name: '门诊量', type: 'line', smooth: true, symbol: ((values || []).length <= 3 ? 'circle' : 'none'), symbolSize: 9,
            data: values,
            lineStyle: { width: 2.5, color: '#00d4ff', shadowColor: 'rgba(0,212,255,0.6)', shadowBlur: 12 },
            areaStyle: {
                color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                    { offset: 0, color: 'rgba(0,212,255,0.38)' },
                    { offset: 1, color: 'rgba(0,212,255,0.02)' }
                ])
            }
        }]
    }, true);
}

// ---- 门诊/住院收入趋势：双柱组合 ----
function renderRevenueTrend(days, mz, zy) {
    charts.revenueTrend.setOption({
        tooltip: Object.assign({ trigger: 'axis', axisPointer: { type: 'shadow' } }, TOOLTIP_STYLE),
        legend: {
            top: 0, right: 10, textStyle: { color: '#6f9cc9', fontSize: 12 },
            itemWidth: 14, itemHeight: 9
        },
        grid: { left: 52, right: 26, top: 34, bottom: 30 },
        xAxis: Object.assign({ type: 'category', data: days }, AXIS_STYLE),
        yAxis: Object.assign({ type: 'value', name: '万元' }, AXIS_STYLE),
        series: [
            {
                name: '门诊收入', type: 'bar', barMaxWidth: 14, data: mz,
                itemStyle: {
                    borderRadius: [3, 3, 0, 0],
                    color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                        { offset: 0, color: '#2f7bff' }, { offset: 1, color: 'rgba(47,123,255,0.15)' }
                    ])
                }
            },
            {
                name: '住院收入', type: 'bar', barMaxWidth: 14, data: zy,
                itemStyle: {
                    borderRadius: [3, 3, 0, 0],
                    color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                        { offset: 0, color: '#00e0c6' }, { offset: 1, color: 'rgba(0,224,198,0.15)' }
                    ])
                }
            }
        ]
    }, true);
}

// ---- 各社区收入·毛利对比：横向双柱（选中社区高亮，其余变暗） ----
var selectedCommunity = 'ALL';

function highlightCommunity(c) {
    if (selectedCommunity === c) return;
    selectedCommunity = c;
    // 用最近一次数据重绘
    if (lastCommunityData) {
        renderCommunity(lastCommunityData.names, lastCommunityData.revenue,
            lastCommunityData.gross, c);
    }
}
var lastCommunityData = null;

function renderCommunity(names, revenue, gross, selected) {
    lastCommunityData = { names: names, revenue: revenue, gross: gross };
    var dimOpacity = function (name) {
        return (selected && selected !== 'ALL' && name !== selected) ? 0.3 : 1;
    };
    charts.community.setOption({
        tooltip: Object.assign({ trigger: 'axis', axisPointer: { type: 'shadow' } }, TOOLTIP_STYLE),
        legend: {
            top: 0, right: 10, textStyle: { color: '#6f9cc9', fontSize: 12 },
            itemWidth: 14, itemHeight: 9
        },
        grid: { left: 56, right: 42, top: 30, bottom: 10, containLabel: false },
        xAxis: Object.assign({ type: 'value', name: '万元' }, AXIS_STYLE),
        yAxis: Object.assign({
            type: 'category', data: names,
            axisLabel: { color: '#9fc3e8', fontSize: 13 },
            splitLine: { show: false }
        }, AXIS_STYLE, { splitLine: { show: false } }),
        series: [
            {
                name: '收入', type: 'bar', barMaxWidth: 12,
                data: revenue.map(function (v, i) {
                    return { value: v, itemStyle: { opacity: dimOpacity(names[i]) } };
                }),
                itemStyle: { borderRadius: [0, 3, 3, 0], color: '#2f7bff' }
            },
            {
                name: '毛利额', type: 'bar', barMaxWidth: 12,
                data: gross.map(function (v, i) {
                    return { value: v, itemStyle: { opacity: dimOpacity(names[i]) } };
                }),
                itemStyle: { borderRadius: [0, 3, 3, 0], color: '#00d4ff' }
            }
        ]
    }, true);
}

// ---- 今日分时门诊量：渐变面积图 ----
function renderHourly(hours, values) {
    charts.hourly.setOption({
        tooltip: Object.assign({ trigger: 'axis' }, TOOLTIP_STYLE),
        grid: { left: 46, right: 26, top: 24, bottom: 26 },
        xAxis: Object.assign({ type: 'category', data: hours, boundaryGap: false }, AXIS_STYLE),
        yAxis: Object.assign({ type: 'value', name: '人次' }, AXIS_STYLE),
        series: [{
            name: '门诊人次', type: 'line', smooth: true, symbol: 'circle',
            symbolSize: 5, data: values,
            lineStyle: { width: 2.5, color: '#00e0c6', shadowColor: 'rgba(0,224,198,0.5)', shadowBlur: 10 },
            itemStyle: { color: '#00e0c6' },
            areaStyle: {
                color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                    { offset: 0, color: 'rgba(0,224,198,0.35)' },
                    { offset: 1, color: 'rgba(0,224,198,0.02)' }
                ])
            }
        }]
    }, true);
}

// ---- 费用分类构成：横向条形图（TOP7+其他，万元） ----
function renderCategory(names, revenues) {
    // 倒序显示（金额最大的在最上方）
    var n = names.slice().reverse();
    var v = revenues.slice().reverse();
    charts.category.setOption({
        tooltip: Object.assign({ trigger: 'axis', axisPointer: { type: 'shadow' },
            formatter: function (ps) {
                var p = ps[0];
                return p.name + '<br/>' + p.marker + '收入 ' + p.value + ' 万元';
            }
        }, TOOLTIP_STYLE),
        grid: { left: 8, right: 46, top: 8, bottom: 6, containLabel: true },
        xAxis: Object.assign({ type: 'value', name: '万元' }, AXIS_STYLE),
        yAxis: Object.assign({
            type: 'category', data: n,
            axisLabel: { color: '#9fc3e8', fontSize: 12.5 },
            splitLine: { show: false }
        }, AXIS_STYLE, { splitLine: { show: false } }),
        series: [{
            name: '收入', type: 'bar', barMaxWidth: 12, data: v,
            label: { show: true, position: 'right', color: '#6f9cc9', fontSize: 12 },
            itemStyle: {
                borderRadius: [0, 3, 3, 0],
                color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
                    { offset: 0, color: 'rgba(47,123,255,0.35)' },
                    { offset: 1, color: '#00d4ff' }
                ])
            }
        }]
    }, true);
}

// ---- 近30天出入院趋势：双折线 ----
function renderAdmission(dates, admitted, discharged) {
    charts.admission.setOption({
        tooltip: Object.assign({ trigger: 'axis' }, TOOLTIP_STYLE),
        legend: {
            top: 0, right: 10, textStyle: { color: '#6f9cc9', fontSize: 12 },
            itemWidth: 14, itemHeight: 9
        },
        grid: { left: 36, right: 24, top: 30, bottom: 26 },
        xAxis: Object.assign({ type: 'category', data: dates, boundaryGap: false }, AXIS_STYLE),
        yAxis: Object.assign({ type: 'value', name: '人' }, AXIS_STYLE),
        series: [
            {
                name: '入院', type: 'line', smooth: true, symbol: ((admitted || []).length <= 3 ? 'circle' : 'none'), symbolSize: 9, data: admitted,
                lineStyle: { width: 2.2, color: '#00d4ff' }, itemStyle: { color: '#00d4ff' },
                areaStyle: {
                    color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                        { offset: 0, color: 'rgba(0,212,255,0.22)' },
                        { offset: 1, color: 'rgba(0,212,255,0.01)' }
                    ])
                }
            },
            {
                name: '出院', type: 'line', smooth: true, symbol: ((discharged || []).length <= 3 ? 'circle' : 'none'), symbolSize: 9, data: discharged,
                lineStyle: { width: 2.2, color: '#ffb054' }, itemStyle: { color: '#ffb054' }
            }
        ]
    }, true);
}

// ==================== 数据渲染 ====================
function renderAll(data) {
    // ---- 区间核心量 ----
    var t = data.kpiPrimary || {};
    animateNum(document.getElementById('kpiOutpatient'), t.outpatientCount);
    var dOut = fmtDelta(t.outpatientCount, t.outpatientCountPrev);
    var elOut = document.getElementById('kpiOutpatientDelta');
    elOut.textContent = dOut.text; elOut.className = 'delta ' + dOut.cls;

    animateNum(document.getElementById('kpiInpatientCurrent'), t.inpatientAtEnd);
    animateNum(document.getElementById('kpiAdmitted'), t.admitted);
    animateNum(document.getElementById('kpiDischarged'), t.discharged);

    animateNum(document.getElementById('kpiRevenueTotal'), t.revenueTotal);
    document.getElementById('kpiRevenueMz').textContent = fmtWan(t.revenueMz) + ' 万';
    document.getElementById('kpiRevenueZy').textContent = fmtWan(t.revenueZy) + ' 万';

    // ---- 经营质量 ----
    var m = data.kpiMargin || {};
    document.getElementById('kpiMarginRate').textContent = fmtPct(m.marginRate);
    document.getElementById('kpiGrossMonth').textContent = fmtWan(m.grossProfit) + ' 万';
    var elMg = document.getElementById('kpiMarginDelta');
    if (m.marginRate != null && m.marginRatePrev != null && !isNaN(m.marginRate) && !isNaN(m.marginRatePrev)) {
        var pp = (Number(m.marginRate) - Number(m.marginRatePrev)).toFixed(1);
        elMg.textContent = (pp > 0 ? '+' : '') + pp + 'pp';
        elMg.className = 'delta ' + (pp > 0 ? 'up' : (pp < 0 ? 'down' : 'flat'));
    } else { elMg.textContent = '--'; elMg.className = 'delta flat'; }

    document.getElementById('kpiOutpatientPrice').textContent =
        m.outpatientPrice == null ? '--' : Number(m.outpatientPrice).toFixed(2);
    // 客单价的两个构成项都取左栏同一快照（kpiPrimary），否则同屏会出现
    // 两个「今日门诊人次」， 它们来自不同刷新时刻，看着就像数据错了。
    document.getElementById('kpiPriceMz').textContent = fmtWan(t.revenueMz) + ' 万';
    document.getElementById('kpiOutpatientMonth').textContent =
        Number(t.outpatientCount || 0).toLocaleString('zh-CN');

    // ---- 图表 ----
    var tr = data.trend || {};
    renderOutpatientTrend(tr.days || [], tr.outpatient || []);
    renderRevenueTrend(tr.days || [], tr.revenueMz || [], tr.revenueZy || []);

    var hr = data.hourly || {};
    renderHourly(hr.hours || [], hr.outpatient || []);

    var cm = data.community || {};
    renderCommunity(cm.names || [], cm.revenue || [], cm.gross || [], selectedCommunity);

    var ct = data.category || {};
    renderCategory(ct.names || [], ct.revenues || []);

    var am = data.admission || {};
    renderAdmission(am.dates || [], am.admitted || [], am.discharged || []);
}

// ==================== 更新时间徽标 ====================
// data.stale === true 是与后端的约定：这一屏是**上次成功的真实数字**，但本次实时查询失败了。
// 与「本次连数据都没拿到」（renderUnavailable）共用同一个降级徽标 ，
// #demoBadge 原用来标「演示数据」，演示回落已删除，现在复用为降级标注（文字由 JS 改写）。
function renderUpdated(data) {
    var badge = document.getElementById('updatedBadge');
    var demo = document.getElementById('demoBadge');
    var ts = data.generatedAt || data.serverTime;
    var txt = '数据更新于 --:--';
    if (ts) {
        var d = new Date(ts);
        function p(n) { return n < 10 ? '0' + n : '' + n; }
        txt = '数据更新于 ' + p(d.getHours()) + ':' + p(d.getMinutes());
    }
    badge.innerHTML = '<span class="dot"></span>' + txt;

    var isStale = data.stale === true;
    // 同一个降级徽标：取数失败但给的是上次真实值时标「数据未更新」，什么都拿不到时标「数据不可用」
    demo.textContent = isStale ? '数据未更新' : '数据不可用';
    demo.style.display = isStale ? '' : 'none';
    badge.classList.toggle('stale', isStale);
}

// 取数/渲染失败：画面**保留上一次成功数据**（绝不伪造数字），但必须显式标注不可用 ，
// 大屏挂在墙上，旁边没人点刷新；不标注就等于把旧数当实时数播出去。
function renderUnavailable(reason) {
    var demo = document.getElementById('demoBadge');
    if (demo) {
        demo.textContent = '数据不可用';
        demo.style.display = '';
    }
    var badge = document.getElementById('updatedBadge');
    if (badge) badge.classList.add('stale');
    renderMissingBanner([]);   // 上一次的「指标未取得」提示已过时，先撤掉
    var bar = document.getElementById('rangeBar');
    if (bar) {
        bar.classList.add('error');
        bar.textContent = '⚠ 数据不可用：' + (reason || '数据接口请求失败')
            + (hasRenderedData ? '；画面为上一次成功的数据，请勿当作实时数' : '；暂无可展示数据');
        bar.style.display = '';
    }
}

// ==================== 拉取后端数据 ====================
var FETCH_INTERVAL = 60 * 1000;
// 请求序号：慢响应（自定义区间）不得盖掉后发起的新响应（60 秒轮询）
var _screenReqSeq = 0;
// 是否成功渲染过一次真实数据，决定降级提示说「保留上次数据」还是「暂无数据」
var hasRenderedData = false;

// 区间档位必须与后端 ScreenRange 的预设名一一对应，改这里要同时改那边。
var RANGE_PRESETS = [
    { key: 'today', label: '今日' },
    { key: '7d', label: '近7天' },
    { key: '30d', label: '近30天' },
    { key: 'thisMonth', label: '本月' },
    { key: 'lastMonth', label: '上月' }
];

var state = {
    community: 'ALL',            // 当前选中社区（ALL=全部）
    range: { preset: 'today' }    // 当前区间：预设档 或 {start,end}（默认档须与后端 ScreenRange.DEFAULT_PRESET 一致）
};
var lastChipsKey = '';
var lastRangeKey = '';

// 顶栏区间切换按钮
function renderRangeChips(activeRange) {
    var box = document.getElementById('rangeSelect');
    if (!box) return;
    var activeKey = activeRange && activeRange.preset ? activeRange.preset : '';
    var key = activeKey;
    if (key === lastRangeKey) return;
    lastRangeKey = key;
    box.innerHTML = RANGE_PRESETS.map(function (p) {
        return '<button class="range-chip' + (p.key === activeKey ? ' active' : '')
            + '" data-r="' + p.key + '">' + p.label + '</button>';
    }).join('');
    Array.prototype.forEach.call(box.querySelectorAll('.range-chip'), function (btn) {
        btn.addEventListener('click', function () {
            state.range = { preset: this.dataset.r };
            renderRangeChips({ preset: this.dataset.r });
            fetchScreenData();
        });
    });
}

function applyCustomRange() {
    var s = document.getElementById('rangeStart');
    var e = document.getElementById('rangeEnd');
    if (!s.value || !e.value) {
        showRangeError('请同时选择起始与结束日期');
        return;
    }
    state.range = { start: s.value, end: e.value };
    renderRangeChips({ preset: '' });   // 自定义时不高亮任何档位
    fetchScreenData();
}

// 区间错误要显示出来而不是悄悄回退，回退会让人误以为看到的就是所选区间
var rangeErrTimer = null;
function showRangeError(msg) {
    var bar = document.getElementById('rangeBar');
    if (!bar) return;
    bar.textContent = '⚠ ' + msg;
    bar.classList.add('error');
    bar.style.display = '';
    if (rangeErrTimer) clearTimeout(rangeErrTimer);
    rangeErrTimer = setTimeout(function () { bar.classList.remove('error'); }, 6000);
}

/* 自定义面板标题（后台「报表管理 → 大屏指标」里配的）。
 * 必须在 updateRangeLabels 之后跑：其中四个标题平时按所选区间自动换字
 * （今日门诊量 / 区间门诊量），用户手填过就以他的为准、不再自动换。
 * 默认文字在第一次遇到某个 id 时记下来，后台把标题清空后，下一次轮询就能还原，
 * 而不是停在旧的自定义值上（那会让人以为清空没生效）。
 * 槽位清单由接口给（data.panelTitleSlots），前端不抄第二份；id 不存在就跳过，绝不新建节点。 */
var _panelTitleDefaults = {};
function applyPanelTitles(data) {
    var slots = (data && data.panelTitleSlots) || [];
    var titles = (data && data.panelTitles) || {};
    for (var i = 0; i < slots.length; i++) {
        var id = slots[i];
        var el = document.getElementById(id);
        if (!el) continue;
        if (!(id in _panelTitleDefaults)) _panelTitleDefaults[id] = el.textContent;
        var custom = String(titles[id] || '').trim();
        el.textContent = custom || _panelTitleDefaults[id];
    }
}

// 面板标题随区间改写：口径统一后，标题必须精确描述窗口，不能仍写「今日/本月」
function updateRangeLabels(r) {
    if (!r) return;
    var label = r.label || '';
    var single = r.days === 1;
    var set = function (id, text) {
        var el = document.getElementById(id);
        if (el) el.textContent = text;
    };
    set('labelOutpatient', single ? '今日门诊量' : '区间门诊量');
    set('labelRevenue', single ? '今日收入' : '区间收入');
    set('labelOutpatientTrend', single ? '门诊量趋势（含当日）' : '区间门诊量趋势');
    // 分时图：单日就是当日 24 小时曲线；跨日是各日同时段合计
    set('labelHourly', single ? '今日分时门诊量' : '区间分时门诊量（各日同时段合计）');

    // 「在院人数」只有当前时点可查（zhuangt 是此刻的状态标志，不是历史快照）。
    // 历史区间里它是 null → 显示 --，这里补上原因，否则看屏的人会以为数据丢了或归零。
    var supported = r.inpatientSupported !== false;
    var inLabel = document.getElementById('labelInpatient');
    if (inLabel) {
        inLabel.textContent = supported ? '在院人数' : '期末在院人数';
        inLabel.title = supported ? '' :
            '在院人数取自住院状态标志，只反映「此刻」，无法回溯到历史日期；如需该指标请把区间切到「今日」';
    }

    var bar = document.getElementById('rangeBar');
    if (bar) {
        bar.classList.remove('error');
        bar.textContent = '当前区间 ' + label + '（' + r.start + ' ~ ' + r.end + '，共 ' + r.days
            + ' 天）· 对比区间 ' + r.prevStart + ' ~ ' + r.prevEnd
            + ' · 下列全部指标均按此窗口统计';
        bar.style.display = '';
    }
}

// 面板标题上的社区标注
function updateCommunityLabels(selected) {
    var label = selected === 'ALL' ? '全部社区' : selected + '社区';
    var map = {
        subOutpatientTrend: '人次 · ',
        subRevenueTrend: '万元 · ',
        subHourly: '人次 · ',
        subCategory: '万元 · ',
        subAdmission: '人次 · ',
        subCommunity: ''
    };
    Object.keys(map).forEach(function (id) {
        var el = document.getElementById(id);
        if (el) el.textContent = map[id] + label;
    });
}
// 顶栏社区切换按钮
function renderCommunityChips(communities, selected) {
    var box = document.getElementById('communitySelect');
    if (!box) return;
    if (!Array.isArray(communities) || communities.length === 0) {
        // 无可见社区：清空切换按钮，避免残留上一次的选项
        if (lastChipsKey !== '') { box.innerHTML = ''; lastChipsKey = ''; }
        return;
    }
    var key = communities.join(',') + '|' + selected;
    if (key === lastChipsKey) return; // 列表和选中态没变就不重建（避免点击时闪烁）
    lastChipsKey = key;
    var html = '<button class="community-chip' + (selected === 'ALL' ? ' active' : '')
        + '" data-c="ALL">全部社区</button>';
    communities.forEach(function (c) {
        html += '<button class="community-chip' + (c === selected ? ' active' : '')
            + '" data-c="' + esc(c) + '">' + esc(c) + '社区</button>';
    });
    box.innerHTML = html;
    Array.prototype.forEach.call(box.querySelectorAll('.community-chip'), function (btn) {
        btn.addEventListener('click', function () {
            if (this.dataset.c === state.community) return;
            state.community = this.dataset.c;
            box.querySelectorAll('.community-chip').forEach(function (b) {
                b.classList.toggle('active', b.dataset.c === state.community);
            });
            fetchScreenData(); // 立即按所选社区重取（服务端内存过滤，秒回）
        });
    });
}

// 面板标题上的社区标注见上方 updateCommunityLabels（区间标注在 updateRangeLabels 里）

function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
}

async function readErrorCode(res) {
    try {
        var text = await res.text();
        if (!text) return '';
        var body = JSON.parse(text);
        return body && body.code ? body.code : '';
    } catch (e) {
        return '';
    }
}

// 无可见社区提示：账号未配置「可查看社区」且未指定大屏号（普通用户）时，
// 服务端返回 noCommunityAccess=true，此时所有社区维度数据均为空，需明确告知用户原因
function renderNoCommunityHint(show) {
    var id = 'noCommunityHint';
    var el = document.getElementById(id);
    if (!show) {
        if (el && el.parentNode) el.parentNode.removeChild(el);
        return;
    }
    if (el) return;
    el = document.createElement('div');
    el.id = id;
    el.textContent = '当前账号未配置可查看社区，暂无数据展示；请联系管理员在「用户管理 - 大屏号」中授权。';
    el.style.cssText = 'position:fixed;left:50%;top:16px;transform:translateX(-50%);z-index:9999;'
        + 'background:rgba(255,209,102,0.18);color:#FFD166;border:1px solid rgba(255,209,102,0.45);'
        + 'padding:8px 20px;border-radius:6px;font-size:16px;white-space:nowrap;';
    document.body.appendChild(el);
}

// 部分指标未取得时必须显式说明：大屏上「--」和「0」很容易被忽略，
// 而「区间还在取数」与「业务真的归零」是完全不同的结论。
function renderMissingBanner(ids) {
    var id = 'missingMetricsHint';
    var el = document.getElementById(id);
    if (!Array.isArray(ids) || ids.length === 0) {
        if (el && el.parentNode) el.parentNode.removeChild(el);
        return;
    }
    if (!el) {
        el = document.createElement('div');
        el.id = id;
        el.style.cssText = 'position:fixed;left:50%;bottom:18px;transform:translateX(-50%);z-index:9999;'
            + 'background:rgba(255,209,102,0.16);color:#ffd166;border:1px solid rgba(255,209,102,0.45);'
            + 'padding:8px 22px;border-radius:4px;font-size:15px;white-space:nowrap;';
        document.body.appendChild(el);
    }
    el.textContent = '⚠ 所选区间有 ' + ids.length + ' 项指标未取得（显示为 --，不代表数值为 0），'
        + '自定义区间首次加载需实时查询，请稍候或改用预设档位';
}

async function fetchScreenData() {
    var bar = document.querySelector('.screen-filterbar');
    if (bar) bar.classList.add('busy');   // 自定义区间可能跑几秒，没有进行态会被当成没点上
    // 请求序号（同 app.js 的 _queryGeneration）：自定义区间慢响应回来时，
    //   60 秒轮询的 today 结果可能已经刷过屏了，旧的这份必须整个丢弃，
    //   否则慢请求反而把新数据盖掉，而标题写的是新区间。
    var reqId = ++_screenReqSeq;
    try {
        var url = '/api/screen/data?community=' + encodeURIComponent(state.community);
        if (state.range.preset) {
            url += '&range=' + encodeURIComponent(state.range.preset);
        } else {
            url += '&start=' + encodeURIComponent(state.range.start)
                 + '&end=' + encodeURIComponent(state.range.end);
        }
        var res = await fetch(url, { credentials: 'include' });
        if (reqId !== _screenReqSeq) return;   // 已有更新的请求在跑/已跑完
        if (res.status === 401) {
            // 会话过期（被顶下线）走带提示的登录页，其余情况回普通登录页
            var code401 = await readErrorCode(res);
            window.location.href = (code401 === 'SESSION_EXPIRED') ? '/login?expired' : '/login';
            return;
        }
        if (res.status === 403) {
            // 无大屏权限 或 首登未改密：回工作台（由 app.js 弹改密框或隐藏大屏入口）
            window.location.href = '/';
            return;
        }
        if (res.status === 400) {
            // 区间非法：保留上一次画面，明确报错，绝不回退到默认区间
            var b400 = await res.json().catch(() => ({}));
            showRangeError(b400.error || '所选区间无效');
            return;
        }
        if (!res.ok) throw new Error('HTTP ' + res.status);
        var body = await res.json();
        var data = body && body.data ? body.data : body;
        if (reqId !== _screenReqSeq) return;   // 解析期间可能已被更新的结果取代
        if (!data || (!data.kpiPrimary && !data.trend)) {
            // 响应结构不对：宁可不刷，也不编数字
            renderUnavailable('接口未返回可渲染的数据');
            return;
        }
        renderNoCommunityHint(data.noCommunityAccess === true);
        renderMissingBanner(data.missingMetrics);
        renderCommunityChips(data.communities, data.selectedCommunity || 'ALL');
        renderRangeChips(data.range);
        updateRangeLabels(data.range);
        applyPanelTitles(data);   // 必须在上一行之后：手填的标题要盖掉按区间自动换的字
        updateCommunityLabels(data.selectedCommunity || 'ALL');
        highlightCommunity(data.selectedCommunity || 'ALL');
        renderAll(data);
        renderUpdated(data);
        hasRenderedData = true;
    } catch (e) {
        // 接口异常 / 非 2xx / JSON 解析失败 / 渲染抛错：
        // 保留上一次成功画面，只点亮「数据不可用」，**绝不回落演示数据**。
        if (reqId !== _screenReqSeq) return;
        // warn 而非 error：断网/后端重启是运行期常态，
        // 「大屏页新增 console.error」被约定为回归信号，故这里刻意不用 error。
        console.warn('大屏数据接口不可用，保留上次画面:', e && e.message ? e.message : e);
        renderUnavailable(e && e.message ? e.message : '数据接口请求失败');
    } finally {
        if (reqId === _screenReqSeq && bar) bar.classList.remove('busy');
    }
}

// ==================== 入口 ====================
window.addEventListener('DOMContentLoaded', function () {
    fitScale();
    tickClock();
    initCharts();
    // 徽标默认文案还是「演示数据」（screen.html 里写死的），演示回落已删除 →
    // 首屏响应前必须自己接管文案并藏起来，否则开屏几秒会在墙上显示「演示数据」。
    var b0 = document.getElementById('demoBadge');
    if (b0) { b0.textContent = '数据不可用'; b0.style.display = 'none'; }
    var apply = document.getElementById('rangeApply');
    if (apply) apply.addEventListener('click', applyCustomRange);
    fetchScreenData();
    setInterval(fetchScreenData, FETCH_INTERVAL);
});
