/**
 * TraceGuard 集成演示用 Mock 服务器
 * 同时模拟 Jira（:18080）、禅道（:18081）、企业微信机器人（:18082）、钉钉机器人（:18083）的 REST API，
 * 后端 JiraClient / ZentaoClient / WeComClient / DingTalkClient 无需修改即可对接。
 * 仅使用 Node 内置 http 模块，无需 npm install。
 */
const http = require('http');
const { URL } = require('url');

function send(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json;charset=utf-8' });
  res.end(JSON.stringify(obj));
}
function log(tag, method, pathname, body) {
  const t = new Date().toISOString().slice(11, 19);
  console.log(`[${t}] ${tag} ${method} ${pathname}${body ? '  body=' + body : ''}`);
}

let jiraSeq = 100;
let zentaoSeq = 100;
// 入站同步演示用：可手动改 issue 状态（demo 脚本或手动 POST /__set_status 模拟远程变更）
const jiraStatusStore = {};
const zentaoStatusStore = {};

// ---------------- Jira Mock (18080) ----------------
const jira = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  let body = '';
  req.on('data', c => (body += c));
  req.on('end', () => {
    log('JIRA', req.method, u.pathname, body);
    if (u.pathname === '/rest/api/2/myself') return send(res, 200, { displayName: 'Mock Jira Admin' });
    const trans = u.pathname.match(/^\/rest\/api\/2\/issue\/([^/]+)\/transitions$/);
    if (trans && req.method === 'GET') {
      return send(res, 200, { transitions: [
        { id: '1', name: 'In Progress' },
        { id: '2', name: 'Done' },
        { id: '3', name: "Won't Fix" }
      ] });
    }
    if (trans && req.method === 'POST') return send(res, 204, {});
    const issue = u.pathname.match(/^\/rest\/api\/2\/issue(?:\/([^/]+))?$/);
    if (issue && req.method === 'POST') {
      const key = 'DEMO-' + (++jiraSeq);
      return send(res, 201, { key });
    }
    if (issue && req.method === 'PUT') return send(res, 204, {});
    // 入站同步轮询：返回issue当前状态（mock 默认为 In Progress，演示可手动改状态）
    const statusQ = u.pathname.match(/^\/rest\/api\/2\/issue\/([^/]+)$/) && u.searchParams.has('fields');
    if (statusQ) {
      const key = u.pathname.split('/').pop();
      const st = jiraStatusStore[key] || 'In Progress';
      return send(res, 200, { key, fields: { status: { name: st } } });
    }
    return send(res, 404, { error: 'not found' });
  });
});

// ---------------- Zentao Mock (18081) ----------------
const zentao = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  let body = '';
  req.on('data', c => (body += c));
  req.on('end', () => {
    log('ZENTAO', req.method, u.pathname, body);
    if (u.pathname === '/api.php/v1/user') return send(res, 200, { id: 1, account: 'mock' });
    if (u.pathname.match(/^\/api\.php\/v1\/bugs\/\d+\/(resolve|close)$/) && req.method === 'POST') {
      return send(res, 200, {});
    }
    if (u.pathname.match(/^\/api\.php\/v1\/(stories|bugs)\/\d+$/) && req.method === 'PUT') {
      return send(res, 200, {});
    }
    const create = u.pathname.match(/^\/api\.php\/v1\/products\/(\d+)\/(stories|bugs)$/);
    if (create && req.method === 'POST') {
      const id = ++zentaoSeq;
      return send(res, 201, { id });
    }
    // 入站同步轮询：GET /api.php/v1/bugs/{id} 返回状态
    const bugQ = u.pathname.match(/^\/api\.php\/v1\/bugs\/(\d+)$/);
    if (bugQ && req.method === 'GET') {
      const id = bugQ[1];
      const st = zentaoStatusStore[id] || 'active';
      return send(res, 200, { id, status: st });
    }
    return send(res, 404, { error: 'not found' });
  });
});

// ---------------- WeCom Mock (18082) ----------------
const wecom = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  let body = '';
  req.on('data', c => (body += c));
  req.on('end', () => {
    log('WECOM', req.method, u.pathname + u.search, body);
    if (u.pathname === '/cgi-bin/webhook/send' && req.method === 'POST') {
      return send(res, 200, { errcode: 0, errmsg: 'ok' });
    }
    return send(res, 404, { errcode: 404, errmsg: 'not found' });
  });
});

// ---------------- DingTalk Mock (18083) ----------------
const dingtalk = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  let body = '';
  req.on('data', c => (body += c));
  req.on('end', () => {
    log('DINGTALK', req.method, u.pathname + u.search, body);
    if (u.pathname === '/robot/send' && req.method === 'POST') {
      return send(res, 200, { errcode: 0, errmsg: 'ok' });
    }
    return send(res, 404, { errcode: 404, errmsg: 'not found' });
  });
});

// OPS-11：统一监听入口——端口占用/权限错误时输出明确错误并退出（原实现端口冲突仅部分启动且静默）
function listenOrExit(server, port, label) {
  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      console.error(`[ERROR] ${label} 端口 ${port} 已被占用，请先停掉占用进程（start-all-mocks.ps1 会自动清理旧实例）`);
    } else {
      console.error(`[ERROR] ${label} 监听端口 ${port} 失败: ${err.message}`);
    }
    process.exit(1);
  });
  server.listen(port, () => console.log(`${label} 已启动: http://localhost:${port}`));
}
listenOrExit(jira, 18080, 'Jira  mock');
listenOrExit(zentao, 18081, '禅道 mock');
listenOrExit(wecom, 18082, '企微 mock');
listenOrExit(dingtalk, 18083, '钉钉 mock');

// ---------------- 调试端点（演示用：模拟远程状态变更，触发入站同步） ----------------
// POST /__simulate  body: {"platform":"jira","key":"DEMO-101","status":"Done"}
//                   {"platform":"zentao","key":"bug-101","status":"resolved"}
const debug = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  let body = '';
  req.on('data', c => (body += c));
  req.on('end', () => {
    if (u.pathname === '/__simulate' && req.method === 'POST') {
      try {
        const p = JSON.parse(body || '{}');
        if (p.platform === 'jira') jiraStatusStore[p.key] = p.status;
        else if (p.platform === 'zentao') zentaoStatusStore[String(p.key).replace('bug-', '')] = p.status;
        return send(res, 200, { ok: true });
      } catch (e) {
        return send(res, 400, { ok: false, error: e.message });
      }
    }
    return send(res, 404, { error: 'not found' });
  });
});
listenOrExit(debug, 18084, '调试端点');
