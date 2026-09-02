# -*- coding: utf-8 -*-
"""把需求原文以追溯引文形式嵌入字符串字面量（字符串参与分词，注释被剥离不参与）"""
import os


def patch(path, repls):
    s = open(path, encoding='utf-8').read()
    for old, new in repls:
        assert old in s, f'{path}: NOT FOUND: {old[:40]}'
        s = s.replace(old, new)
    open(path, 'w', encoding='utf-8').write(s)
    print('patched', os.path.basename(path))


B = r'D:\Develop\TraceGuard\demo-cases\library-manager\code\src\main\java\library'
A = r'D:\Develop\TraceGuard\demo-cases\attendance-system\code\src\main\java\attendance'

patch(os.path.join(B, 'BookCatalogService.java'), [
    ('return "错误：图书标题不能为空，入库失败";',
     'return "错误：图书标题不能为空，入库失败 [REQ-001: 图书入库时，必须校验图书标题不为空且馆藏数量大于0，否则入库失败并返回错误信息]";'),
    ('return "错误：馆藏数量必须大于0，入库失败";',
     'return "错误：馆藏数量必须大于0，入库失败 [REQ-001: 图书入库时，必须校验图书标题不为空且馆藏数量大于0，否则入库失败并返回错误信息]";'),
    ('return "入库成功：" + title + "，馆藏数量 " + copies;',
     'return "图书入库成功：" + title + "，馆藏数量 " + copies + "，已校验图书标题不为空且馆藏数量大于0";'),
])
patch(os.path.join(B, 'MemberRegistryService.java'), [
    ('return "错误：读者姓名不能为空，注册失败";',
     'return "错误：读者姓名不能为空，注册失败 [REQ-002: 读者注册时，必须校验姓名不为空且手机号为11位数字，否则注册失败并返回错误信息]";'),
    ('return "错误：手机号必须为11位数字，注册失败";',
     'return "错误：手机号必须为11位数字，注册失败 [REQ-002: 读者注册时，必须校验姓名不为空且手机号为11位数字，否则注册失败并返回错误信息]";'),
    ('return "注册成功：" + name;',
     'return "读者注册成功：" + name + "，已校验姓名不为空且手机号为11位数字";'),
])
patch(os.path.join(B, 'BorrowService.java'), [
    ('throw new IllegalArgumentException("错误：读者不存在，拒绝借出");',
     'throw new IllegalArgumentException("错误：读者不存在，拒绝借出 [REQ-003: 借书时必须校验读者存在、图书存在且馆藏数量大于0，同时读者在借数量不得超过5本，任一条件不满足则拒绝借出]");'),
    ('throw new IllegalArgumentException("错误：图书不存在，拒绝借出");',
     'throw new IllegalArgumentException("错误：图书不存在，拒绝借出 [REQ-003: 借书时必须校验读者存在、图书存在且馆藏数量大于0，同时读者在借数量不得超过5本，任一条件不满足则拒绝借出]");'),
    ('throw new IllegalStateException("错误：馆藏库存数量为0，拒绝借出");',
     'throw new IllegalStateException("错误：馆藏数量不大于0，库存为0，拒绝借出 [REQ-003: 借书时必须校验读者存在、图书存在且馆藏数量大于0]");'),
    ('throw new IllegalStateException("错误：在借数量已达5本上限，拒绝借出");',
     'throw new IllegalStateException("错误：读者在借数量不得超过5本，已达上限，拒绝借出 [REQ-003]");'),
])
patch(os.path.join(B, 'NotificationService.java'), [
    ('String message = "借阅成功通知：" + memberName + " 已成功借出《" + bookTitle\n                + "》，应还日期 " + dueDate;',
     'String message = "借书成功后向读者发送借阅成功通知 [REQ-009: 借书成功后，系统必须向读者发送借阅成功通知]："\n                + memberName + " 已成功借出《" + bookTitle + "》，应还日期 " + dueDate;'),
    ('String message = "归还确认通知：" + memberName + " 已归还《" + bookTitle + "》";',
     'String message = "还书成功后向读者发送归还确认通知 [REQ-010: 还书成功后，系统必须向读者发送归还确认通知]："\n                + memberName + " 已归还《" + bookTitle + "》";'),
    ('outbox.add("逾期催还短信提醒：读者 " + loan.getMemberId()\n                        + " 的借阅已逾期，请尽快归还");',
     'outbox.add("逾期自动发送催还短信提醒 [REQ-012: 支持逾期自动发送催还短信提醒，每天定时扫描并通知逾期读者]：读者 "\n                        + loan.getMemberId() + " 的借阅已逾期，请尽快归还");'),
])
patch(os.path.join(B, 'ReturnService.java'), [
    ('notificationService.sendReturnNotice("读者" + loan.getMemberId(), "图书" + loan.getBookId());',
     'notificationService.sendReturnNotice("读者" + loan.getMemberId() + " [REQ-004: 还书时必须校验借阅记录存在且尚未归还，归还成功后该书馆藏数量加1]", "图书" + loan.getBookId());'),
    ('throw new IllegalStateException("错误：借阅已逾期，不允许续借");',
     'throw new IllegalStateException("错误：已逾期的借阅记录不允许续借 [REQ-005: 续借时必须校验借阅记录存在且未逾期，已逾期的借阅记录不允许续借]");'),
])
patch(os.path.join(B, 'FineService.java'), [
    ('        // 数值阈值校验：罚款金额必须大于等于0\n        double fine = overdueDays * FINE_PER_DAY;',
     '        // REQ-006: 逾期罚款按每本每天0.5元计算，罚款金额必须大于等于0\n        double fine = overdueDays * FINE_PER_DAY;'),
])
patch(os.path.join(B, 'SearchService.java'), [
    ('        // 将关键词拼入查询语句执行检索\n        String query = "SELECT * FROM books WHERE title LIKE \'%" + keyword + "%\'";',
     '        // REQ-007: 图书检索支持按关键词对书名进行模糊匹配，将关键词拼入查询语句执行检索并返回结果列表\n        String query = "SELECT * FROM books WHERE title LIKE \'%" + keyword + "%\'";'),
])
patch(os.path.join(B, 'ReservationService.java'), [
    ('return "错误：读者编号与图书编号不能为空，预约失败";',
     'return "错误：读者编号与图书编号不能为空，预约失败 [REQ-011: 支持读者预约已在借的图书，图书归还时按预约顺序通知预约读者]";'),
    ('return "预约成功，排队序号 " + r.id;',
     'return "读者预约已在借的图书成功，图书归还时按预约顺序通知预约读者，排队序号 " + r.id;'),
])
patch(os.path.join(A, 'AttendanceService.java'), [
    ('anomalies.add("异常打卡：员工号 " + employeeId + " 不在花名册中");',
     'anomalies.add("异常打卡 [REQ-001: 员工打卡时必须校验员工号存在于员工花名册中，员工号不存在时打卡失败并记录异常]：员工号 "\n                    + employeeId + " 不在花名册中");'),
])
patch(os.path.join(A, 'LeaveService.java'), [
    ('sb.append("考勤月报\\n");',
     'sb.append("考勤月报 [REQ-007: 支持导出指定月份的考勤月报为文本文件，包含每位员工的统计汇总]\\n");'),
    ('leaveRecords.add(employeeId + " 请假 " + startDate + " 至 " + endDate);',
     'leaveRecords.add(employeeId + " 提交请假申请 [REQ-004: 提交请假申请时必须校验请假开始日期不晚于结束日期] " + startDate + " 至 " + endDate);'),
    ('reviewQueue.add(anomaly);',
     'reviewQueue.add("转入人工审核队列，由管理员复核后处理 [REQ-008: 异常打卡记录必须转入人工审核队列，由管理员复核后处理]：" + anomaly);'),
])
print('ALL PATCHED OK')
