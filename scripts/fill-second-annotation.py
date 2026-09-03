# -*- coding: utf-8 -*-
"""① 回填：把 second-annotation.json 的盲评结论写入 consistency-labels.json 的
secondAnnotator 字段（annotator/label/agreement），并打印 κ 与验证集 LLM 口径摘要。"""
import json

BS = 'samples/dataset/'
sec = json.load(open(BS + 'second-annotation.json', encoding='utf-8'))
labels = json.load(open(BS + 'consistency-labels.json', encoding='utf-8'))

verdicts = sec['verdicts']
agree = n = a_def = b_def = both_def = 0
val = {'tp': 0, 'fp': 0, 'fn': 0, 'tn': 0}

for p in labels['pairs']:
    if not p.get('requirementCode') or p.get('scope') == 'exclude':
        continue
    v = verdicts.get(p['id'])
    if v is None:
        p['secondAnnotator'] = None   # 盲评弃权/未对齐
        continue
    n += 1
    second_defective = not v['consistent']
    first_defective = p['label'] == 'defective'
    p['secondAnnotator'] = {
        'annotator': sec['annotator'],
        'label': 'defective' if second_defective else 'consistent',
        'defectType': v.get('defectType', ''),
        'agreement': first_defective == second_defective,
    }
    if first_defective == second_defective:
        agree += 1
    if first_defective:
        a_def += 1
    if second_defective:
        b_def += 1
    if first_defective and second_defective:
        both_def += 1
    key = ('tp' if second_defective else 'tn') if first_defective else \
          ('fp' if second_defective else 'tn')
    if first_defective:
        val['tp' if second_defective else 'fn'] += 1
    else:
        val['fp' if second_defective else 'tn'] += 1

po = agree / n
pe = (a_def / n) * (b_def / n) + ((n - a_def) / n) * ((n - b_def) / n)
kappa = 1.0 if pe == 1 else (po - pe) / (1 - pe)
tp, fp, fn, tn = val['tp'], val['fp'], val['fn'], val['tn']
acc = (tp + tn) / (tp + tn + fp + fn)
miss = fn / max(1, tp + fn)
fpr = fp / max(1, fp + tn)

sec['summary'] = {
    'n': n, 'agreement_rate': round(po, 4), 'kappa': round(kappa, 4),
    'first_defective': a_def, 'second_defective': b_def,
    'validation_llm': {'M': tp + tn + fp + fn, 'acc': round(acc, 4),
                       'miss': round(miss, 4), 'fpr': round(fpr, 4),
                       'TP': tp, 'FP': fp, 'FN': fn, 'TN': tn},
}
json.dump(sec, open(BS + 'second-annotation.json', 'w', encoding='utf-8'),
          ensure_ascii=False, indent=1)
json.dump(labels, open(BS + 'consistency-labels.json', 'w', encoding='utf-8'),
          ensure_ascii=False, indent=1)
print('n=%d po=%.4f pe=%.4f kappa=%.4f' % (n, po, pe, kappa))
print('validation LLM: M=%d acc=%.1f%% miss=%.1f%% fpr=%.1f%% TP/FP/FN/TN=%d/%d/%d/%d'
      % (tp + tn + fp + fn, acc * 100, miss * 100, fpr * 100, tp, fp, fn, tn))
