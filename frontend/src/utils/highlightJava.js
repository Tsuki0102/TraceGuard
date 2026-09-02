/**
 * 轻量 Java 语法高亮（个性化增强 BATCH-2 #11）
 * 逐行分词并输出带 .tok-* 类名的 HTML；所有片段输出前转义，杜绝 XSS。
 * 配色令牌见 index.css 的 .tok-* 区段（深浅色自适应）。
 */

const KEYWORDS = new Set([
  'public', 'private', 'protected', 'class', 'interface', 'enum', 'record',
  'static', 'final', 'void', 'int', 'long', 'double', 'float', 'boolean',
  'char', 'byte', 'short', 'var', 'new', 'return', 'if', 'else', 'for',
  'while', 'do', 'switch', 'case', 'break', 'continue', 'try', 'catch',
  'finally', 'throw', 'throws', 'extends', 'implements', 'import', 'package',
  'this', 'super', 'null', 'true', 'false', 'instanceof', 'default',
  'abstract', 'synchronized', 'volatile', 'transient', 'native', 'assert',
  'sealed', 'permits', 'yield'
])

const TOKEN_RE = /(\/\/[^\n]*)|("(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?)|(@[A-Za-z_]\w*)|(\b\d[\d_]*(?:\.\d+)?[fFdDlL]?\b)|([A-Za-z_$][\w$]*)/g

const escapeHtml = (s) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

const span = (cls, text) =>
  cls ? `<span class="tok-${cls}">${escapeHtml(text)}</span>` : escapeHtml(text)

/** 单行 Java 代码 → 高亮 HTML（无换行上下文，注释/字符串不跨行） */
export function highlightJava(line) {
  if (!line) return ''
  let out = ''
  let last = 0
  TOKEN_RE.lastIndex = 0
  let m
  while ((m = TOKEN_RE.exec(line)) !== null) {
    // 匹配之间的普通文本
    if (m.index > last) out += escapeHtml(line.slice(last, m.index))
    last = m.index + m[0].length
    if (m[1]) {
      out += span('com', m[1])                       // 行注释
    } else if (m[2]) {
      out += span('str', m[2])                       // 字符串/字符
    } else if (m[3]) {
      out += span('ann', m[3])                       // 注解
    } else if (m[4]) {
      out += span('num', m[4])                       // 数字
    } else if (m[5]) {
      const word = m[5]
      if (KEYWORDS.has(word)) {
        out += span('kw', word)                      // 关键字
      } else {
        // 后随 "(" 视为方法调用
        const rest = line.slice(last)
        out += span(/^\s*\(/.test(rest) ? 'fn' : '', word)
      }
    }
  }
  out += escapeHtml(line.slice(last))
  return out
}

export default highlightJava
