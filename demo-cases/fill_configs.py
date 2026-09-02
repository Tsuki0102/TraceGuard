# -*- coding: utf-8 -*-
"""填充 sys_config：需求解析规则（词库加富）/ 代码解析范围 / 质量门槛"""
import json
import subprocess

MYSQL = r'D:\Develop\MySQL\MySQL Server 8.0\bin\mysql.exe'

rules = {
    "ambiguityKeywords": [
        "等", "等等", "适当", "合适", "合理", "可能", "也许", "若干", "一些", "方便",
        "友好", "尽快", "大量", "部分", "基本", "大约", "左右", "普遍", "通常", "尽量",
        "相关", "某些", "较多", "较小", "明显", "较好", "改善", "提升", "优化",
        "完善的", "良好的", "快速的", "稳定的", "高效的"
    ],
    "contradictionPairs": [
        ["必须", "禁止"], ["必须", "不得"], ["禁止", "(?<!不)允许"], ["不得", "(?<!不)允许"],
        ["启用", "禁用"], ["应当", "不得"], ["全部", "部分"], ["增加", "减少"]
    ],
    "enableLLM": False
}
scope = {
    "includePackages": ["com.traceguard.service", "com.traceguard.core", "com.traceguard.util"],
    "excludePackages": ["com.traceguard.entity", "com.traceguard.mapper"],
    "includeClasses": ["ConsistencyChecker", "AnalysisService", "LibraryService"],
    "excludeClasses": ["*Test", "*Config"],
    "includeMethods": [],
    "excludeMethods": ["toString", "equals", "hashCode"]
}
gate = {"coverage": 85, "consistency": 80, "seriousLimit": 6}


def sql_escape(s):
    return s.replace("\\", "\\\\").replace("'", "''")


def upsert(key, value, desc):
    sql = (
        "INSERT INTO sys_config (config_key, config_value, description, updated_by, updated_at) "
        "VALUES ('{k}', '{v}', '{d}', 'admin', NOW()) "
        "ON DUPLICATE KEY UPDATE config_value=VALUES(config_value), description=VALUES(description), "
        "updated_by='admin', updated_at=NOW();"
    ).format(k=key, v=sql_escape(value), d=desc)
    subprocess.run([MYSQL, '-uroot', '-p123456', '--default-character-set=utf8mb4',
                    'traceguard', '-e', sql], capture_output=True, text=True)
    print('upserted', key)


upsert('req_parse_rules', json.dumps(rules, ensure_ascii=False), '需求解析规则（歧义/模糊词 + 互斥词对）')
upsert('code_parse_scope', json.dumps(scope, ensure_ascii=False), '代码解析范围（FR-CODE-001：按包/类/方法过滤解析）')
upsert('quality_gate', json.dumps(gate), '质量门槛：覆盖率/一致率/严重缺陷上限')
print('DONE')
