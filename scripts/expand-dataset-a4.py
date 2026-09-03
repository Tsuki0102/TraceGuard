# -*- coding: utf-8 -*-
"""A4 数据集扩容脚本：向 consistency-labels.json 追加 ticket-system / library-system 标注对，
为既有 65 对补 split=tune，新数据 split=validation（纯 hold-out），并加双评留位字段。"""
import json
from collections import Counter

def P(i, src, req, f, m, label, dtype=None, did=None, note=None):
    p = {"id": i, "source": src, "requirementCode": req, "codeFile": f, "method": m,
         "label": label, "split": "validation", "secondAnnotator": None}
    if dtype:
        p["defectType"] = dtype
    if did:
        p["defectId"] = did
    if note:
        p["note"] = note
    return p

new_pairs = []
# ---- ticket-system 一致对（20）----
new_pairs += [
    P("CL-101", "ticket-system", "REQ-T003", "TicketService.java", "createTicket", "consistent"),
    P("CL-102", "ticket-system", "REQ-T004", "TicketStateValidator.java", "validateTransition", "consistent"),
    P("CL-103", "ticket-system", "REQ-T006", "TicketStateValidator.java", "canAccept", "consistent"),
    P("CL-104", "ticket-system", "REQ-T019", "TicketService.java", "resolveTicket", "consistent"),
    P("CL-105", "ticket-system", "REQ-T020", "TicketService.java", "batchImport", "consistent"),
    P("CL-106", "ticket-system", "REQ-T012", "TicketQueryService.java", "queryById", "consistent"),
    P("CL-107", "ticket-system", "REQ-T013", "TicketQueryService.java", "queryByStatus", "consistent"),
    P("CL-108", "ticket-system", "REQ-T015", "TicketQueryService.java", "countByDate", "consistent"),
    P("CL-109", "ticket-system", "REQ-T021", "TicketQueryService.java", "searchByKeyword", "consistent"),
    P("CL-110", "ticket-system", "REQ-T017", "TicketQueryService.java", "queryPaged", "consistent",
      note="困难负例：需求表述分页规则，实现为分页参数裁剪，措辞迥异语义一致"),
    P("CL-111", "ticket-system", "REQ-T018", "TicketValidator.java", "containsSensitiveWord", "consistent",
      note="困难负例：需求'敏感词过滤'，实现为本地词表匹配"),
    P("CL-112", "ticket-system", "REQ-T022", "TicketValidator.java", "hasDeletePermission", "consistent"),
    P("CL-113", "ticket-system", "REQ-T024", "TicketValidator.java", "validateAttachmentSize", "consistent"),
    P("CL-114", "ticket-system", "REQ-T016", "SlaCalculator.java", "countSlaBreaches", "consistent",
      note="困难负例：SLA常量缺陷归属 computeDeadlineHours（单点归因），统计逻辑自身一致"),
    P("CL-115", "ticket-system", "REQ-T026", "TicketArchiveService.java", "archiveExpiredTickets", "consistent"),
    P("CL-116", "ticket-system", "REQ-T016", "SlaCalculator.java", "isBreached", "consistent",
      note="困难负例：超时判断逻辑自身一致，时限常量缺陷归属他处"),
    P("CL-117", "ticket-system", "REQ-T002", "TicketService.java", "createTicket", "consistent",
      note="入口委托校验模式（镜像既有 CL-002 口径）"),
    P("CL-118", "ticket-system", "REQ-T018", "TicketService.java", "createTicket", "consistent"),
    P("CL-119", "ticket-system", "REQ-T008", "Ticket.java", "setCloseTime", "consistent"),
    P("CL-120", "ticket-system", "REQ-T009", "Ticket.java", "getReopenCount", "consistent"),
    P("CL-121", "ticket-system", "REQ-T010", "Ticket.java", "setPriority", "consistent"),
]
# ---- ticket-system 缺陷对（11）----
new_pairs += [
    P("CL-130", "ticket-system", "REQ-T001", "TicketValidator.java", "validateTitle", "defective",
      "业务逻辑不一致", "D-T01", note="困难负例：结构语义高度对齐，仅数值 100≠50"),
    P("CL-131", "ticket-system", "REQ-T002", "TicketValidator.java", "validatePriority", "defective",
      "约束条件不满足", "D-T03"),
    P("CL-132", "ticket-system", "REQ-T005", "SlaCalculator.java", "computeDeadlineHours", "defective",
      "业务逻辑不一致", "D-T09", note="困难负例：分支齐备仅数值 4h≠2h"),
    P("CL-133", "ticket-system", "REQ-T007", "TicketService.java", "assignTicket", "defective",
      "业务逻辑不一致", "D-T05"),
    P("CL-134", "ticket-system", "REQ-T008", "TicketStateValidator.java", "canClose", "defective",
      "约束条件不满足", "D-T07"),
    P("CL-135", "ticket-system", "REQ-T008", "TicketService.java", "closeTicket", "defective",
      "业务逻辑不一致", "D-T06"),
    P("CL-136", "ticket-system", "REQ-T009", "TicketService.java", "reopenTicket", "defective",
      "约束条件不满足", "D-T04"),
    P("CL-137", "ticket-system", "REQ-T010", "TicketService.java", "escalateIfOverdue", "defective",
      "业务逻辑不一致", "D-T08"),
    P("CL-138", "ticket-system", "REQ-T011", "NotificationService.java", "notifyAssignee", "defective",
      "业务逻辑不一致", "D-T11"),
    P("CL-139", "ticket-system", "REQ-T014", "TicketQueryService.java", "queryByAssignee", "defective",
      "约束条件不满足", "D-T10"),
    P("CL-140", "ticket-system", "REQ-T001", "TicketService.java", "createTicket", "consistent",
      note="入口委托校验模式：标题校验缺陷归属 validateTitle"),
]
# ---- ticket-system 范围外（3）----
new_pairs += [
    P("CL-150", "ticket-system", "", "TicketQueryService.java", "dumpDebugSnapshot", "defective",
      "代码超范围实现", "D-T12"),
    P("CL-151", "ticket-system", "", "TicketArchiveService.java", "notifyArchiveResultByMail", "defective",
      "代码超范围实现", "D-T13"),
    P("CL-152", "ticket-system", "", "Ticket.java", "getFinishEvaluation", "defective",
      "需求缺失", "D-T14"),
]
# ---- library-system 一致对（18）----
new_pairs += [
    P("CL-201", "library-system", "REQ-L004", "BorrowService.java", "borrowBook", "consistent",
      note="困难负例：多步流程（扣库存/生成记录/更新在借数）依次完成"),
    P("CL-202", "library-system", "REQ-L005", "BorrowService.java", "borrowBook", "consistent"),
    P("CL-203", "library-system", "REQ-L007", "FineCalculator.java", "computeFine", "consistent",
      note="困难负例：费率与封顶分两段需求文字，实现以常量+min封顶表达，措辞迥异语义一致"),
    P("CL-204", "library-system", "REQ-L011", "FineCalculator.java", "isCreditBlockedByDebt", "consistent"),
    P("CL-205", "library-system", "REQ-L013", "LibraryQueryService.java", "reserveBook", "consistent"),
    P("CL-206", "library-system", "REQ-L014", "LibraryQueryService.java", "cancelReservation", "consistent"),
    P("CL-207", "library-system", "REQ-L015", "LibraryQueryService.java", "queryBorrowingByReader", "consistent"),
    P("CL-208", "library-system", "REQ-L017", "LibraryQueryService.java", "countBorrowingByCategory", "consistent"),
    P("CL-209", "library-system", "REQ-L018", "LibraryQueryService.java", "searchByIsbn", "consistent",
      note="英文需求对"),
    P("CL-210", "library-system", "REQ-L019", "BorrowService.java", "reissueCard", "consistent"),
    P("CL-211", "library-system", "REQ-L020", "BookManageService.java", "addBook", "consistent"),
    P("CL-212", "library-system", "REQ-L020", "BorrowValidator.java", "validateBookInfo", "consistent"),
    P("CL-213", "library-system", "REQ-L003", "BorrowValidator.java", "validateStock", "consistent"),
    P("CL-214", "library-system", "REQ-L003", "BorrowService.java", "borrowBook", "consistent",
      note="入口委托库存校验"),
    P("CL-215", "library-system", "REQ-L002", "BorrowService.java", "borrowBook", "consistent",
      note="入口委托额度校验（额度常量缺陷归属 validateQuota）"),
    P("CL-216", "library-system", "REQ-L001", "BorrowService.java", "borrowBook", "consistent",
      note="入口委托读者证校验（有效期缺陷归属 validateCardValidity）"),
    P("CL-217", "library-system", "REQ-L013", "Book.java", "getStock", "consistent"),
    P("CL-218", "library-system", "REQ-L015", "Book.java", "getReaderId", "consistent",
      note="借阅记录关联读者ID的字段能力（Book.java 内嵌 BorrowRecord）"),
]
# ---- library-system 缺陷对（9）----
new_pairs += [
    P("CL-230", "library-system", "REQ-L002", "BorrowValidator.java", "validateQuota", "defective",
      "业务逻辑不一致", "D-L01", note="困难负例：校验结构完整仅数值 3≠5"),
    P("CL-231", "library-system", "REQ-L006", "BorrowService.java", "returnBook", "defective",
      "业务逻辑不一致", "D-L02"),
    P("CL-232", "library-system", "REQ-L008", "BorrowService.java", "returnBook", "defective",
      "业务逻辑不一致", "D-L03"),
    P("CL-233", "library-system", "REQ-L009", "BorrowService.java", "renewBook", "defective",
      "业务逻辑不一致", "D-L04"),
    P("CL-234", "library-system", "REQ-L010", "BorrowService.java", "renewBook", "defective",
      "约束条件不满足", "D-L05"),
    P("CL-235", "library-system", "REQ-L001", "BorrowValidator.java", "validateCardValidity", "defective",
      "约束条件不满足", "D-L06"),
    P("CL-236", "library-system", "REQ-L012", "FineCalculator.java", "isCreditBlockedByOverdue", "defective",
      "业务逻辑不一致", "D-L07", note="困难负例：仅数值 30≠15"),
    P("CL-237", "library-system", "REQ-L016", "LibraryQueryService.java", "queryOverdue", "defective",
      "业务逻辑不一致", "D-L08", note="细节困难例：排序方向反转"),
    P("CL-238", "library-system", "REQ-L011", "BorrowService.java", "borrowBook", "defective",
      "约束条件不满足", "D-L09", note="冻结/欠款检查缺失，检查职责归属借书入口"),
]
# ---- library-system 范围外（2）----
new_pairs += [
    P("CL-250", "library-system", "", "BookManageService.java", "sendOverdueReminder", "defective",
      "代码超范围实现", "D-L10"),
    P("CL-251", "library-system", "", "BookManageService.java", "compressCoverImage", "defective",
      "代码超范围实现", "D-L11"),
]

path = 'samples/dataset/consistency-labels.json'
d = json.load(open(path, encoding='utf-8'))
for p in d['pairs']:
    p['split'] = 'tune'  # 既有 65 对全部为调参集（历史标定均在其上进行）
    p.setdefault('secondAnnotator', None)
d['pairs'].extend(new_pairs)

in_scope = [p for p in d['pairs'] if p.get('requirementCode') and p.get('scope') != 'exclude']
bs = {}
for p in d['pairs']:
    bs.setdefault(p['source'], {'tune': 0, 'validation': 0})
    bs[p['source']][p.get('split', 'tune')] += 1
summary = {
    "total": len(d['pairs']),
    "inScope": len(in_scope),
    "consistent": sum(1 for p in in_scope if p['label'] == 'consistent'),
    "defective": sum(1 for p in in_scope if p['label'] == 'defective'),
    "excluded_scope": sum(1 for p in d['pairs'] if p.get('scope') == 'exclude'),
    "out_of_scope": sum(1 for p in d['pairs'] if not p.get('requirementCode')),
    "by_source": dict(Counter(p['source'] for p in d['pairs'])),
    "by_split": dict(Counter(p.get('split', 'tune') for p in d['pairs'])),
    "by_source_split": bs,
}
d['summary'] = summary
d['description'] += (" A4 扩容（2026-09-03）：新增 ticket-system/library-system 两个样本域共 65 对"
                     "（split=validation 纯 hold-out 验证集，未参与任何阈值/权重调参），既有 65 对 split=tune"
                     "（调参集）；secondAnnotator 字段为双评留位（κ 待第二标注人复标后回填）。")
json.dump(d, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print("labels total:", summary['total'], "inScope:", summary['inScope'],
      "consistent:", summary['consistent'], "defective:", summary['defective'],
      "by_split:", summary['by_split'])
