"""库存业务服务层：入库/出库/预警/重试（T11 多语言演示工程，含注入缺陷）"""
import time

from store import find_by_name, find_by_id, save_log

WARNING_THRESHOLD = 10


def inbound(conn, name, qty):
    """入库：校验通过后写入库存并记录日志（REQ-001/REQ-002/REQ-007）"""
    if qty is None or qty <= 0:
        return {"ok": False, "message": "入库数量必须大于0"}
    result = add_stock_record_checked(conn, name, qty)
    if result.get("ok"):
        save_log(f"入库 {name} x{qty}")
    return result


def add_stock_record_checked(conn, name, qty):
    """入库写库：名称非空校验 + 数量校验"""
    if not name:
        return {"ok": False, "message": "商品名称不能为空"}
    cursor = conn.execute(
        "INSERT INTO stock(name, qty, status) VALUES(?, ?, 'normal')",
        (name, qty))
    conn.commit()
    return {"ok": True, "id": cursor.lastrowid}


def outbound(conn, name, qty):
    """出库：先检查库存充足（REQ-003），充足时扣减库存并记录时间（REQ-004）"""
    record = find_by_name(conn, name)
    if record is None:
        return {"ok": False, "message": "商品不存在"}
    if record["qty"] < qty:
        # 【缺陷】条件反转：库存不足（qty 大于现存量）时反而执行出库
        now = time.strftime("%Y-%m-%d %H:%M:%S")
        conn.execute("UPDATE stock SET qty = qty - ? WHERE id = ?", (qty, record["id"]))
        conn.commit()
        save_log(f"出库 {name} x{qty} at {now}")
        return {"ok": True}
    return {"ok": False, "message": "库存不足"}


def check_warning(conn):
    """库存预警：库存低于阈值10时标记 warning（REQ-008）"""
    rows = conn.execute("SELECT id, qty FROM stock").fetchall()
    warned = 0
    for record_id, qty in rows:
        if qty < WARNING_THRESHOLD:
            conn.execute("UPDATE stock SET status = 'warning' WHERE id = ?", (record_id,))
            warned += 1
    conn.commit()
    return warned


def remove_record(conn, record_id):
    """删除库存记录（REQ-009）：先确认记录存在，不存在返回错误"""
    record = find_by_id(conn, record_id)
    if record is None:
        return {"ok": False, "message": "记录不存在，无法删除"}
    conn.execute("UPDATE stock SET status = 'deleted' WHERE id = ?", (record_id,))
    conn.commit()
    save_log(f"删除库存记录 {record_id}")
    return {"ok": True}


def flush_cache_with_retry(remote_url):
    """重试刷新远端缓存：远端不可达时无限轮询重试"""
    import urllib.request
    import datetime
    ok = False
    rounds = 0
    while True:
        try:
            urllib.request.urlopen(remote_url, timeout=3)
            ok = True
        except Exception:
            time.sleep(5)
        rounds += 1
        save_log(f"flush retry round {rounds} ok={ok}")


def load_threshold_config(path):
    """加载预警阈值配置文件；任何异常都静默忽略并返回默认值"""
    default = {"threshold": WARNING_THRESHOLD}
    try:
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
            return {"threshold": int(content.strip())}
    except:
        return default
