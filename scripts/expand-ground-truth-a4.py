# -*- coding: utf-8 -*-
"""A4 补充：追加 7 个标注对凑足 in-scope 120+；写入 defect-ground-truth（行号自动定位）。"""
import json

path = 'samples/dataset/consistency-labels.json'
d = json.load(open(path, encoding='utf-8'))
extra = [
    {"id": "CL-122", "source": "ticket-system", "requirementCode": "REQ-T004", "codeFile": "Ticket.java",
     "method": "setStatus", "label": "consistent", "split": "validation", "secondAnnotator": None,
     "note": "状态字段支持状态机流转"},
    {"id": "CL-123", "source": "ticket-system", "requirementCode": "REQ-T006", "codeFile": "TicketService.java",
     "method": "acceptTicket", "label": "consistent", "split": "validation", "secondAnnotator": None,
     "note": "受理主实现：校验+状态置 OPEN"},
    {"id": "CL-124", "source": "ticket-system", "requirementCode": "REQ-T015", "codeFile": "Ticket.java",
     "method": "getCreateTime", "label": "consistent", "split": "validation", "secondAnnotator": None,
     "note": "按日期统计依赖创建时间字段"},
    {"id": "CL-219", "source": "library-system", "requirementCode": "REQ-L005", "codeFile": "Book.java",
     "method": "getDueDate", "label": "consistent", "split": "validation", "secondAnnotator": None,
     "note": "应还日期字段（借出+30天写入）"},
    {"id": "CL-220", "source": "library-system", "requirementCode": "REQ-L009", "codeFile": "Book.java",
     "method": "getRenewCount", "label": "consistent", "split": "validation", "secondAnnotator": None,
     "note": "续借次数字段"},
    {"id": "CL-221", "source": "library-system", "requirementCode": "REQ-L017", "codeFile": "Book.java",
     "method": "getCategory", "label": "consistent", "split": "validation", "secondAnnotator": None,
     "note": "分类字段支持分类统计"},
    {"id": "CL-222", "source": "library-system", "requirementCode": "REQ-L016", "codeFile": "Book.java",
     "method": "getDueDate", "label": "consistent", "split": "validation", "secondAnnotator": None,
     "note": "逾期判定依赖应还日期字段"},
]
existing = {p['id'] for p in d['pairs']}
added = 0
for p in extra:
    if p['id'] not in existing:
        d['pairs'].append(p)
        added += 1

in_scope = [p for p in d['pairs'] if p.get('requirementCode') and p.get('scope') != 'exclude']
d['summary']['total'] = len(d['pairs'])
d['summary']['inScope'] = len(in_scope)
d['summary']['consistent'] = sum(1 for p in in_scope if p['label'] == 'consistent')
d['summary']['defective'] = sum(1 for p in in_scope if p['label'] == 'defective')
bs = {}
for p in d['pairs']:
    bs.setdefault(p['source'], {'tune': 0, 'validation': 0})
    bs[p['source']][p.get('split', 'tune')] += 1
d['summary']['by_source_split'] = bs
d['summary']['by_source'] = {k: v['tune'] + v['validation'] for k, v in bs.items()}
json.dump(d, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print("added:", added, "total:", d['summary']['total'], "inScope:", d['summary']['inScope'])

# ============ defect-ground-truth ============
def find_line(path, pattern):
    with open(path, encoding='utf-8') as f:
        for i, line in enumerate(f, 1):
            if pattern in line:
                return i
    return None

TK = 'samples/ticket-system/code/com/sample/ticket/'
LB = 'samples/library-system/code/com/sample/library/'
gt_new = [
    {"id": "D-T01", "project": "ticket-system", "file": "TicketValidator.java", "method": "validateTitle",
     "type": "业务逻辑不一致", "subType": "长度阈值不符", "relatedReq": "REQ-T001",
     "line": find_line(TK + 'TicketValidator.java', 'length() <= 100')},
    {"id": "D-T03", "project": "ticket-system", "file": "TicketValidator.java", "method": "validatePriority",
     "type": "约束条件不满足", "subType": "优先级枚举校验缺失", "relatedReq": "REQ-T002",
     "line": find_line(TK + 'TicketValidator.java', 'return priority != null')},
    {"id": "D-T04", "project": "ticket-system", "file": "TicketService.java", "method": "reopenTicket",
     "type": "约束条件不满足", "subType": "重开次数上限缺失", "relatedReq": "REQ-T009",
     "line": find_line(TK + 'TicketService.java', 'public void reopenTicket')},
    {"id": "D-T05", "project": "ticket-system", "file": "TicketService.java", "method": "assignTicket",
     "type": "业务逻辑不一致", "subType": "状态流转错误", "relatedReq": "REQ-T007",
     "line": find_line(TK + 'TicketService.java', 'ticket.setStatus(Ticket.STATUS_RESOLVED)')},
    {"id": "D-T06", "project": "ticket-system", "file": "TicketService.java", "method": "closeTicket",
     "type": "业务逻辑不一致", "subType": "关闭时间未记录", "relatedReq": "REQ-T008",
     "line": find_line(TK + 'TicketService.java', 'public void closeTicket')},
    {"id": "D-T07", "project": "ticket-system", "file": "TicketStateValidator.java", "method": "canClose",
     "type": "约束条件不满足", "subType": "关闭状态校验缺失", "relatedReq": "REQ-T008",
     "line": find_line(TK + 'TicketStateValidator.java', 'return ticket != null')},
    {"id": "D-T08", "project": "ticket-system", "file": "TicketService.java", "method": "escalateIfOverdue",
     "type": "业务逻辑不一致", "subType": "升级时限不符", "relatedReq": "REQ-T010",
     "line": find_line(TK + 'TicketService.java', 'hours > 24')},
    {"id": "D-T09", "project": "ticket-system", "file": "SlaCalculator.java", "method": "computeDeadlineHours",
     "type": "业务逻辑不一致", "subType": "SLA时限不符", "relatedReq": "REQ-T005",
     "line": find_line(TK + 'SlaCalculator.java', 'return 4;')},
    {"id": "D-T10", "project": "ticket-system", "file": "TicketQueryService.java", "method": "queryByAssignee",
     "type": "约束条件不满足", "subType": "查询权限校验缺失", "relatedReq": "REQ-T014",
     "line": find_line(TK + 'TicketQueryService.java', 'public List<Ticket> queryByAssignee')},
    {"id": "D-T11", "project": "ticket-system", "file": "NotificationService.java", "method": "notifyAssignee",
     "type": "业务逻辑不一致", "subType": "重试次数不足", "relatedReq": "REQ-T011",
     "line": find_line(TK + 'NotificationService.java', 'int maxRetry = 1;')},
    {"id": "D-T12", "project": "ticket-system", "file": "TicketQueryService.java", "method": "dumpDebugSnapshot",
     "type": "代码超范围实现", "subType": "未在需求内的调试快照", "relatedReq": "",
     "line": find_line(TK + 'TicketQueryService.java', 'public String dumpDebugSnapshot')},
    {"id": "D-T13", "project": "ticket-system", "file": "TicketArchiveService.java", "method": "notifyArchiveResultByMail",
     "type": "代码超范围实现", "subType": "未在需求内的邮件通知", "relatedReq": "",
     "line": find_line(TK + 'TicketArchiveService.java', 'public void notifyArchiveResultByMail')},
    {"id": "D-T14", "project": "ticket-system", "file": "Ticket.java", "method": "getFinishEvaluation",
     "type": "需求缺失", "subType": "满意度评价未实现", "relatedReq": "REQ-T025",
     "line": find_line(TK + 'Ticket.java', 'public String getFinishEvaluation')},
    {"id": "D-L01", "project": "library-system", "file": "BorrowValidator.java", "method": "validateQuota",
     "type": "业务逻辑不一致", "subType": "借阅上限不符", "relatedReq": "REQ-L002",
     "line": find_line(LB + 'BorrowValidator.java', 'getBorrowingCount() < 3')},
    {"id": "D-L02", "project": "library-system", "file": "BorrowService.java", "method": "returnBook",
     "type": "业务逻辑不一致", "subType": "罚款未计入欠款", "relatedReq": "REQ-L006",
     "line": find_line(LB + 'BorrowService.java', 'return fine;')},
    {"id": "D-L03", "project": "library-system", "file": "BorrowService.java", "method": "returnBook",
     "type": "业务逻辑不一致", "subType": "借阅记录未核销", "relatedReq": "REQ-L008",
     "line": find_line(LB + 'BorrowService.java', 'record.setReturnDate(now);')},
    {"id": "D-L04", "project": "library-system", "file": "BorrowService.java", "method": "renewBook",
     "type": "业务逻辑不一致", "subType": "续借次数上限不符", "relatedReq": "REQ-L009",
     "line": find_line(LB + 'BorrowService.java', 'getRenewCount() >= 2')},
    {"id": "D-L05", "project": "library-system", "file": "BorrowService.java", "method": "renewBook",
     "type": "约束条件不满足", "subType": "预约占用检查缺失", "relatedReq": "REQ-L010",
     "line": find_line(LB + 'BorrowService.java', 'public void renewBook')},
    {"id": "D-L06", "project": "library-system", "file": "BorrowValidator.java", "method": "validateCardValidity",
     "type": "约束条件不满足", "subType": "读者证有效期校验缺失", "relatedReq": "REQ-L001",
     "line": find_line(LB + 'BorrowValidator.java', 'return reader != null')},
    {"id": "D-L07", "project": "library-system", "file": "FineCalculator.java", "method": "isCreditBlockedByOverdue",
     "type": "业务逻辑不一致", "subType": "逾期冻结阈值不符", "relatedReq": "REQ-L012",
     "line": find_line(LB + 'FineCalculator.java', 'OVERDUE_FREEZE_DAYS = 30')},
    {"id": "D-L08", "project": "library-system", "file": "LibraryQueryService.java", "method": "queryOverdue",
     "type": "业务逻辑不一致", "subType": "排序方向反转", "relatedReq": "REQ-L016",
     "line": find_line(LB + 'LibraryQueryService.java', 'overdue.sort(Comparator.comparingLong')},
    {"id": "D-L09", "project": "library-system", "file": "BorrowService.java", "method": "borrowBook",
     "type": "约束条件不满足", "subType": "冻结资格检查缺失", "relatedReq": "REQ-L011",
     "line": find_line(LB + 'BorrowService.java', 'public String borrowBook')},
    {"id": "D-L10", "project": "library-system", "file": "BookManageService.java", "method": "sendOverdueReminder",
     "type": "代码超范围实现", "subType": "未在需求内的逾期提醒", "relatedReq": "",
     "line": find_line(LB + 'BookManageService.java', 'public void sendOverdueReminder')},
    {"id": "D-L11", "project": "library-system", "file": "BookManageService.java", "method": "compressCoverImage",
     "type": "代码超范围实现", "subType": "未在需求内的图片压缩", "relatedReq": "",
     "line": find_line(LB + 'BookManageService.java', 'public String compressCoverImage')},
]
missing = [g['id'] for g in gt_new if g['line'] is None]
assert not missing, "行号定位失败: %s" % missing

gtp = 'samples/dataset/defect-ground-truth.json'
gt = json.load(open(gtp, encoding='utf-8'))
have = {x['id'] for x in gt['defects']}
for g in gt_new:
    if g['id'] not in have:
        gt['defects'].append(g)
by_proj = {}
for x in gt['defects']:
    by_proj[x['project']] = by_proj.get(x['project'], 0) + 1
gt['summary'] = {"total": len(gt['defects']), "by_project": by_proj}
gt['description'] = gt.get('description', '') + " A4 扩容（2026-09-03）：新增 ticket-system/library-system 缺陷真值 24 条（含困难负例：数值错配型 D-T01/D-T09/D-L01/D-L07、排序反转 D-L08、归属歧义型计数对 CL-114/116）。"
json.dump(gt, open(gtp, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print("ground truth total:", len(gt['defects']), "by_project:", by_proj)
