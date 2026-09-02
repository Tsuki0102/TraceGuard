# -*- coding: utf-8 -*-
"""演示案例填充驱动：登录 → 创建项目 → 传需求 → 传代码 → 建任务 → 跑分析 → 轮询结果"""
import json
import subprocess
import sys
import time
import urllib.request

BASE = 'http://localhost:8080/api'


def login():
    req = urllib.request.Request(
        BASE + '/auth/login',
        data=json.dumps({'username': 'admin', 'password': 'admin123'}).encode('utf-8'),
        headers={'Content-Type': 'application/json'},
        method='POST')
    with urllib.request.urlopen(req, timeout=30) as r:
        body = json.loads(r.read().decode('utf-8'))
        assert body['code'] == 200, body
        cookies = []
        for h in r.headers.get_all('Set-Cookie') or []:
            cookies.append(h.split(';')[0])
    if not cookies:
        raise RuntimeError('登录未返回任何 Cookie')
    return '; '.join(cookies)


COOKIE = login()
print('登录成功')


def post_json(path, payload, timeout=120):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
        headers={'Content-Type': 'application/json; charset=utf-8', 'Cookie': COOKIE},
        method='POST')
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8'))


def get_json(path, timeout=60):
    req = urllib.request.Request(BASE + path, headers={'Cookie': COOKIE})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8'))


def upload(path, filepath):
    out = subprocess.run(
        ['curl', '-s', '-X', 'POST', BASE + path, '-F', 'file=@' + filepath,
         '-H', 'Cookie: ' + COOKIE],
        capture_output=True, encoding='utf-8').stdout
    return json.loads(out)


def run_case(name, industry, desc, req_file, zip_file, wait_minutes=6, t1=None, t2=None):
    print('==== 案例:', name)
    created = post_json('/project/create', {
        'projectName': name, 'industryType': industry,
        'techStack': 'Java 8 / 纯JDK', 'description': desc})
    assert created['code'] == 200, created
    pid = created['data']['id']
    print('项目ID:', pid)

    up1 = upload(f'/analysis/upload/requirement/{pid}', req_file)
    print('需求上传:', up1['code'], (up1.get('data') or '')[:40])
    assert up1['code'] == 200, up1

    up2 = upload(f'/analysis/upload/code/{pid}', zip_file)
    print('代码上传:', up2['code'], (up2.get('data') or '')[:40])
    assert up2['code'] == 200, up2

    task_payload = {'projectId': pid, 'taskName': '全量一致性分析'}
    if t1 is not None:
        task_payload['thresholdT1'] = t1
    if t2 is not None:
        task_payload['thresholdT2'] = t2
    task = post_json('/analysis/task/create', task_payload)
    assert task['code'] == 200, task
    tid = task['data']['id']
    print('任务ID:', tid)

    run = post_json(f'/analysis/task/run/{tid}', {})
    print('启动:', run['code'], run.get('message', ''))

    deadline = time.time() + wait_minutes * 60
    status = 'running'
    while time.time() < deadline:
        time.sleep(8)
        lst = get_json(f'/analysis/task/list/{pid}')
        data = lst.get('data')
        tasks = data if isinstance(data, list) else (data or {}).get('records', [])
        if tasks:
            status = tasks[0].get('status', 'unknown')
            print('  状态:', status)
            if status in ('completed', 'failed', 'success', 'done'):
                break
    print('最终状态:', status)
    return pid, tid, status


if __name__ == '__main__':
    which = sys.argv[1] if len(sys.argv) > 1 else 'all'
    results = {}
    if which in ('all', 'library'):
        results['library'] = run_case(
            '图书管理系统', '教育',
            '演示案例：图书借阅/检索/报表全流程，含检索拼接查询与日志巡检场景',
            r'D:\Develop\TraceGuard\demo-cases\library-manager\requirements.txt',
            r'D:\Develop\TraceGuard\demo-cases\library-manager\code.zip')
    if which in ('all', 'inventory'):
        results['inventory'] = run_case(
            'Inventory Management System', '企业管理',
            'Demo case: high req-code alignment, analyzed with tuned threshold T1=0.6/T2=0.4 (FR-CHECK configurable)',
            r'D:\Develop\TraceGuard\demo-cases\inventory-system\requirements.txt',
            r'D:\Develop\TraceGuard\demo-cases\inventory-system\code.zip',
            t1=0.6, t2=0.4)
    if which in ('all', 'attendance'):
        results['attendance'] = run_case(
            '员工考勤管理系统', '企业管理',
            '演示案例：打卡/迟到判定/请假/月报统计，含迟到阈值偏差与请假校验缺失场景',
            r'D:\Develop\TraceGuard\demo-cases\attendance-system\requirements.txt',
            r'D:\Develop\TraceGuard\demo-cases\attendance-system\code.zip')
    print(json.dumps(results, ensure_ascii=False))
