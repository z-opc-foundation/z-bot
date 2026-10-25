import React, {useMemo} from 'react';

/** HTML 转义，防止 XSS */
function escapeHtml(text: string): string {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/** 行内 Markdown 格式渲染（加粗、斜体、行内代码、链接、图片） */
function renderInline(text: string): string {
    let result = escapeHtml(text);
    // 行内代码（先处理，避免内部内容被其他规则干扰）
    result = result.replace(/`([^`]+)`/g, '<code class="md-inline-code">$1</code>');
    // 图片
    result = result.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1" class="md-img">');
    // 链接
    result = result.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
    // 加粗+斜体
    result = result.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
    // 加粗
    result = result.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    // 斜体（加粗已处理，剩余单 * 视为斜体标记）
    result = result.replace(/\*([^*]+?)\*/g, '<em>$1</em>');
    // 删除线
    result = result.replace(/~~(.+?)~~/g, '<del>$1</del>');
    return result;
}

/** 判断是否为特殊行（代码块起始、标题、分割线、引用、列表项） */
function isSpecialLine(line: string): boolean {
    return (
        line.trim().startsWith('```') ||
        /^(#{1,6})\s+/.test(line) ||
        /^---+\s*$/.test(line) ||
        /^>\s/.test(line) ||
        /^\s*[-*+]\s+/.test(line) ||
        /^\s*\d+\.\s+/.test(line)
    );
}

/** 将 Markdown 文本转换为 HTML 字符串 */
function renderMarkdown(md: string): string {
    if (!md) return '';

    const lines = md.split('\n');
    const parts: string[] = [];
    let i = 0;

    while (i < lines.length) {
        const line = lines[i];

        // ── 代码块 ──
        if (line.trim().startsWith('```')) {
            const lang = line.trim().slice(3).trim();
            const codeLines: string[] = [];
            i++;
            while (i < lines.length && !lines[i].trim().startsWith('```')) {
                codeLines.push(lines[i]);
                i++;
            }
            i++; // 跳过闭合 ```
            const langAttr = lang ? ` class="language-${escapeHtml(lang)}"` : '';
            parts.push(`<pre class="md-code-block"><code${langAttr}>${escapeHtml(codeLines.join('\n').trim())}</code></pre>`);
            continue;
        }

        // ── 空行 ──
        if (line.trim() === '') {
            i++;
            continue;
        }

        // ── 标题 ──
        const headerMatch = line.match(/^(#{1,6})\s+(.+)$/);
        if (headerMatch) {
            const level = headerMatch[1].length;
            parts.push(`<h${level} class="md-h${level}">${renderInline(headerMatch[2])}</h${level}>`);
            i++;
            continue;
        }

        // ── 水平分割线 ──
        if (/^---+\s*$/.test(line)) {
            parts.push('<hr class="md-hr">');
            i++;
            continue;
        }

        // ── 引用块 ──
        if (/^>\s/.test(line)) {
            const quoteLines: string[] = [];
            while (i < lines.length && /^>\s/.test(lines[i])) {
                quoteLines.push(lines[i].replace(/^>\s/, ''));
                i++;
            }
            parts.push(`<blockquote class="md-quote">${renderInline(quoteLines.join('\n')).replace(/\n/g, '<br>')}</blockquote>`);
            continue;
        }

        // ── 无序列表 ──
        if (/^\s*[-*+]\s+/.test(line)) {
            const items: string[] = [];
            while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
                items.push(`<li>${renderInline(lines[i].replace(/^\s*[-*+]\s+/, ''))}</li>`);
                i++;
            }
            parts.push(`<ul class="md-ul">${items.join('')}</ul>`);
            continue;
        }

        // ── 有序列表 ──
        if (/^\s*\d+\.\s+/.test(line)) {
            const items: string[] = [];
            while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
                items.push(`<li>${renderInline(lines[i].replace(/^\s*\d+\.\s+/, ''))}</li>`);
                i++;
            }
            parts.push(`<ol class="md-ol">${items.join('')}</ol>`);
            continue;
        }

        // ── 段落（连续非特殊行） ──
        const paraLines: string[] = [];
        while (i < lines.length && lines[i].trim() !== '' && !isSpecialLine(lines[i])) {
            paraLines.push(lines[i]);
            i++;
        }
        if (paraLines.length > 0) {
            const rendered = renderInline(paraLines.join('\n')).replace(/\n/g, '<br>');
            parts.push(`<p class="md-p">${rendered}</p>`);
        }
    }

    return parts.join('\n');
}

interface MarkdownRendererProps {
    content: string;
    className?: string;
}

/** 轻量级 Markdown 渲染组件，不依赖第三方库 */
export const MarkdownRenderer: React.FC<MarkdownRendererProps> = ({content, className}) => {
    const html = useMemo(() => renderMarkdown(content), [content]);
    return (
        <div
            className={`markdown-body ${className || ''}`}
            dangerouslySetInnerHTML={{__html: html}}
        />
    );
};
