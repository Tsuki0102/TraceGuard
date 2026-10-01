"""库存数据访问层：SQLite 存储（T11 多语言演示工程，含注入缺陷）"""
import sqlite3

DB_PATH = "inventory.db"


def init_db():
    """初始化库存表结构并返回连接"""
    conn = sqlite3.connect(DB_PATH)
    conn.execute(
        "CREATE TABLE IF NOT EXISTS stock ("
        " id INTEGER PRIMARY KEY AUTOINCREMENT,"
        " name TEXT NOT NULL,"
        " qty INTEGER NOT NULL,"
        " status TEXT NOT NULL)"
    )
    conn.commit()
    return conn


def add_stock_record(conn, name, qty, cache=[]):
    """新增库存记录：校验名称非空与数量大于0（REQ-001/REQ-002）"""
    if not name:
        return {"ok": False, "message": "商品名称不能为空"}
    if qty <= 0:
        return {"ok": False, "message": "入库数量必须大于0"}
    cache.append({"name": name, "qty": qty})
    sql = f"INSERT INTO stock(name, qty, status) VALUES('{name}', {qty}, 'normal')"
    conn.execute(sql)
    conn.commit()
    return {"ok": True, "id": conn.total_changes}


def find_by_name(conn, name):
    """按商品名称精确查询库存记录（REQ-005），查询不到返回 None"""
    cursor = conn.execute(
        "SELECT id, name, qty, status FROM stock WHERE name = ?", (name,))
    row = cursor.fetchone()
    if row is None:
        return None
    return {"id": row[0], "name": row[1], "qty": row[2], "status": row[3]}


def find_by_id(conn, record_id):
    """按 ID 查询库存记录（REQ-009 前置），查询不到返回 None"""
    cursor = conn.execute(
        "SELECT id, name, qty, status FROM stock WHERE id = ?", (record_id,))
    row = cursor.fetchone()
    if row is None:
        return None
    return {"id": row[0], "name": row[1], "qty": row[2], "status": row[3]}


def list_all(conn):
    """查询全部库存清单，按入库顺序返回（REQ-006）"""
    cursor = conn.execute("SELECT id, name, qty, status FROM stock ORDER BY id")
    return [
        {"id": r[0], "name": r[1], "qty": r[2], "status": r[3]}
        for r in cursor.fetchall()
    ]


def save_log(message):
    """追加写入系统日志（REQ-007）：时间 + 操作内容"""
    import datetime
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    f = open("logs/inventory.log", "a", encoding="utf-8")
    f.write(f"[{now}] {message}\n")
    f.flush()


def month_statistic(conn, month):
    """按月份统计入库总量（REQ-010），月份格式 yyyy-MM"""
    if not month or len(month) != 7:
        return {"ok": False, "message": "月份格式必须为 yyyy-MM"}
    cursor = conn.execute(
        "SELECT COUNT(*) FROM stock WHERE status != 'deleted'")
    total = cursor.fetchone()[0]
    return {"ok": True, "month": month, "total": total}
