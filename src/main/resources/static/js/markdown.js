// Minimal, XSS-safe Markdown renderer: all text is escaped before any tag is generated.
function mdInline(text) {
    const codes = [];
    const masked = text.replace(/[]/g, '').split('`').map((segment, index) => {
        if (index % 2 === 0) return segment;
        codes.push('<code>' + escapeHtml(segment) + '</code>');
        return '' + (codes.length - 1) + '';
    }).join('');
    return escapeHtml(masked)
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\[([^\[\]]*:L\d+[^\[\]]*)\]/g, (match, inner) => {
            const chips = inner.split(',').map(part => {
                const m = part.trim().match(/^([\w./\-]+):L(\d+)(?:-L?(\d+))?$/);
                if (!m) return null;
                const label = m[1].split('/').pop() + ':' + m[2] + (m[3] ? '-' + m[3] : '');
                return '<span class="cite-chip" title="' + m[1] + ':L' + m[2] + (m[3] ? '-L' + m[3] : '') + '">' + label + '</span>';
            });
            return chips.every(Boolean) ? chips.join(' ') : match;
        })
        .replace(/(\d+)/g, (match, n) => codes[Number(n)]);
}

function mdTable(rows) {
    const cells = row => row.trim().replace(/^\||\|$/g, '').split('|').map(cell => mdInline(cell.trim()));
    const hasHeader = rows.length > 1 && /^[\s|:-]+$/.test(rows[1]);
    const head = hasHeader ? `<thead><tr>${cells(rows[0]).map(c => `<th>${c}</th>`).join('')}</tr></thead>` : '';
    const body = rows.slice(hasHeader ? 2 : 0).map(row => `<tr>${cells(row).map(c => `<td>${c}</td>`).join('')}</tr>`);
    return `<div class="md-table"><table>${head}<tbody>${body.join('')}</tbody></table></div>`;
}

const MD_LIST_ITEM = /^\s*([-*]|\d+\.)\s+/;
const MD_BLOCK_START = /^(#{1,4}\s|\s*([-*]|\d+\.)\s+|\s*\||\s*(---|\*\*\*)\s*$)/;

function mdBlocks(text) {
    const lines = text.split('\n');
    const out = [];
    let i = 0;
    while (i < lines.length) {
        const line = lines[i];
        const heading = line.match(/^(#{1,4})\s+(.*)$/);
        if (!line.trim()) {
            i++;
        } else if (heading) {
            const level = heading[1].length + 2;
            out.push(`<h${level} class="md-h">${mdInline(heading[2])}</h${level}>`);
            i++;
        } else if (MD_LIST_ITEM.test(line)) {
            const tag = /^\s*\d+\./.test(line) ? 'ol' : 'ul';
            const items = [];
            while (i < lines.length && MD_LIST_ITEM.test(lines[i])) {
                items.push(`<li>${mdInline(lines[i].replace(MD_LIST_ITEM, ''))}</li>`);
                i++;
            }
            out.push(`<${tag}>${items.join('')}</${tag}>`);
        } else if (/^\s*\|/.test(line)) {
            const rows = [];
            while (i < lines.length && /^\s*\|/.test(lines[i])) rows.push(lines[i++]);
            out.push(mdTable(rows));
        } else if (/^\s*(---|\*\*\*)\s*$/.test(line)) {
            out.push('<hr>');
            i++;
        } else {
            const paragraph = [];
            while (i < lines.length && lines[i].trim() && (paragraph.length === 0 || !MD_BLOCK_START.test(lines[i]))) {
                paragraph.push(lines[i++]);
            }
            out.push(`<p>${paragraph.map(mdInline).join('<br>')}</p>`);
        }
    }
    return out.join('');
}

function renderMarkdown(source) {
    return source.split('```').map((part, index) => {
        if (index % 2 === 0) return mdBlocks(part);
        const newline = part.indexOf('\n');
        const code = newline >= 0 ? part.slice(newline + 1) : part;
        return `<pre class="md-code"><code>${escapeHtml(code.replace(/\n$/, ''))}</code></pre>`;
    }).join('');
}
