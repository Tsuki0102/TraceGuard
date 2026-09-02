# -*- coding: utf-8 -*-
"""持久化 LLM 运行时配置：enabled=true + 全渠道演示 apiKey（走后端加密存储）"""
import json
import subprocess
import urllib.request

BASE = 'http://localhost:8080/api'


def login():
    req = urllib.request.Request(
        BASE + '/auth/login',
        data=json.dumps({'username': 'admin', 'password': 'admin123'}).encode('utf-8'),
        headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(req, timeout=30) as r:
        cookies = []
        for h in r.headers.get_all('Set-Cookie') or []:
            cookies.append(h.split(';')[0])
    return '; '.join(cookies)


COOKIE = login()
print('登录成功')


def api(method, path, payload=None):
    data = json.dumps(payload, ensure_ascii=False).encode('utf-8') if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data,
                                 headers={'Content-Type': 'application/json; charset=utf-8',
                                          'Cookie': COOKIE}, method=method)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode('utf-8'))


cfg = api('GET', '/llm/config')['data']
print('routing:', json.dumps(cfg.get('routing'), ensure_ascii=False))

demo_keys = {
    'glm': 'demo-glm-apikey-2026',
    'deepseek': 'demo-deepseek-apikey-2026',
    'qwen': 'demo-qwen-apikey-2026',
    'local': 'demo-local-apikey-2026',
}
providers = []
for name, p in (cfg.get('providers') or {}).items():
    providers.append({
        'provider': name,
        'baseUrl': p.get('baseUrl'),
        'apiKey': demo_keys.get(name, 'demo-key-2026'),
    })

payload = {
    'enabled': True,
    'providers': providers,
    'routing': cfg.get('routing') or {},
    'models': cfg.get('models') or {},
}
res = api('PUT', '/llm/config', payload)
print('保存结果:', res.get('code'), res.get('message'))

status = api('GET', '/llm/status')['data']
print('enabled:', status.get('enabled'))
for name, p in (status.get('providers') or {}).items():
    print(' ', name, 'apiKeyConfigured:', p.get('apiKeyConfigured'))
