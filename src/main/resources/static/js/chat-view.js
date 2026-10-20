// Assistant message view: stage line, thinking, tool calls, streamed Markdown answer, citations.
const TOOL_LABELS = {
    search_code: { icon: '🔍', text: '检索代码' },
    read_file: { icon: '📄', text: '读取文件' },
    git_log: { icon: '🕘', text: '查看提交历史' }
};

function summarizeToolArguments(name, rawArguments) {
    let args;
    try { args = JSON.parse(rawArguments || '{}'); } catch (e) { return rawArguments || ''; }
    const values = Object.values(args);
    if (name === 'read_file' && values.length >= 3) return `${values[0]} (L${values[1]}-L${values[2]})`;
    if (name === 'search_code' && values.length) return String(values[0]);
    return values.join(', ');
}

const autoScroll = { stuck: true, bound: false };

function bindAutoScroll() {
    if (autoScroll.bound) return;
    autoScroll.bound = true;
    const box = elements.chatMessages;
    box.addEventListener('scroll', () => {
        autoScroll.stuck = box.scrollHeight - box.scrollTop - box.clientHeight < 4;
    });
}

function createAssistantView(messageEl) {
    bindAutoScroll();
    autoScroll.stuck = true;
    const contentEl = messageEl.querySelector('[data-content]');
    contentEl.innerHTML = `
        <div class="trace" data-trace></div>
        <div class="status-line" data-status><span class="loading-dots"><span class="loading-dot"></span><span class="loading-dot"></span><span class="loading-dot"></span></span><span data-phase>正在连接…</span><span class="status-elapsed" data-elapsed></span></div>
        <div class="answer md" data-answer></div>
        <div class="notice-area" data-notices></div>
        <div data-citations></div>`;
    const part = name => contentEl.querySelector(`[data-${name}]`);
    const traceEl = part('trace'), statusEl = part('status'), phaseEl = part('phase');
    const elapsedEl = part('elapsed'), answerEl = part('answer'), noticeEl = part('notices');
    const citationsEl = part('citations');
    const tools = new Map();
    const startedAt = Date.now();
    let thinkingBody = null, thinkingBox = null, thinkingChars = 0;
    let answerText = '', frame = 0, stopped = false;

    const scroll = () => {
        if (autoScroll.stuck) elements.chatMessages.scrollTop = elements.chatMessages.scrollHeight;
    };
    const timer = setInterval(() => { elapsedEl.textContent = `${Math.floor((Date.now() - startedAt) / 1000)}s`; }, 1000);
    const setPhase = text => { if (!stopped) phaseEl.textContent = text; };

    function renderAnswer() {
        frame = 0;
        answerEl.innerHTML = renderMarkdown(answerText.replace(/^\s+/, ''));
        scroll();
    }

    function collapseThinking() {
        if (!thinkingBox) return;
        thinkingBox.open = false;
        thinkingBox.querySelector('summary').textContent = `思考过程（${thinkingChars} 字）`;
        thinkingBox = null;
        thinkingBody = null;
        thinkingChars = 0;
    }

    function ensureTool(id, name) {
        if (tools.has(id)) return tools.get(id);
        const label = TOOL_LABELS[name] || { icon: '🛠', text: name };
        const el = document.createElement('div');
        el.className = 'tool-call running';
        el.innerHTML = `<span class="tool-icon">${label.icon}</span><span class="tool-name">${escapeHtml(label.text)}</span><span class="tool-args"></span><span class="tool-state">执行中…</span>`;
        traceEl.appendChild(el);
        tools.set(id, el);
        return el;
    }

    function stop() {
        stopped = true;
        clearInterval(timer);
        statusEl.hidden = true;
        if (frame) cancelAnimationFrame(frame);
        if (answerText) renderAnswer();
    }

    function addNotice(kind, text) {
        const el = document.createElement('div');
        el.className = `notice ${kind}`;
        el.textContent = text;
        noticeEl.appendChild(el);
        scroll();
    }

    return {
        status(detail) { setPhase(detail); },
        thinking(text) {
            if (!thinkingBox) {
                if (!text.trim()) return;
                thinkingBox = document.createElement('details');
                thinkingBox.className = 'thinking';
                thinkingBox.open = true;
                thinkingBox.innerHTML = '<summary>思考中…</summary><div class="thinking-body"></div>';
                thinkingBody = thinkingBox.querySelector('.thinking-body');
                traceEl.appendChild(thinkingBox);
            }
            thinkingChars += text.length;
            thinkingBody.textContent += text;
            thinkingBody.scrollTop = thinkingBody.scrollHeight;
            setPhase('模型思考中');
            scroll();
        },
        toolStart(event) {
            collapseThinking();
            const el = ensureTool(event.id, event.name);
            el.querySelector('.tool-args').textContent = summarizeToolArguments(event.name, event.arguments);
            setPhase(`正在${(TOOL_LABELS[event.name] || { text: event.name }).text}`);
            scroll();
        },
        toolEnd(event) {
            const el = ensureTool(event.id, event.name);
            el.className = `tool-call ${event.ok ? 'done' : 'failed'}`;
            el.querySelector('.tool-state').textContent = event.ok ? `完成 · ${event.resultChars} 字符` : '失败';
            setPhase('模型整理结果中');
        },
        token(text) {
            if (!answerText && !text.trim()) return;
            if (!answerText) { collapseThinking(); setPhase('正在生成回答'); }
            answerText += text;
            if (!frame) frame = requestAnimationFrame(renderAnswer);
        },
        citations(list) {
            if (!list.length) return;
            citationsEl.innerHTML = `<div class="citations"><div class="citations-title">📎 代码引用</div><div class="citation-list">${list.map(c => `
                <div class="citation-item"><div class="citation-path">${escapeHtml(c.path)} [L${c.startLine}-L${c.endLine}]</div>
                ${c.explanation ? `<div class="citation-desc">${escapeHtml(c.explanation)}</div>` : ''}</div>`).join('')}</div></div>`;
            scroll();
        },
        finish(done) {
            stop();
            collapseThinking();
            if (!answerText.trim()) addNotice('warn', '模型没有返回文字内容。');
            if (done && done.unverifiedCitations && done.unverifiedCitations.length) {
                addNotice('warn', `有 ${done.unverifiedCitations.length} 处引用未能在检索到的代码中核实，请自行确认：${done.unverifiedCitations.join('、')}`);
            }
            if (done && done.uncited) addNotice('warn', '这条回答没有给出代码引用，内容未经核实，请谨慎参考。');
            if (done && done.truncated) addNotice('warn', '回答被输出长度上限截断。可在「模型设置」里调大「最大输出 Token」后重新提问。');
            if (done) {
                const seconds = ((done.durationMs || 0) / 1000).toFixed(1);
                addNotice('meta', done.cached ? '来自缓存' : `用时 ${seconds}s · 工具调用 ${done.toolCalls || 0} 次`);
            }
        },
        fail(message) {
            stop();
            collapseThinking();
            addNotice('error', answerText.trim() ? `回答中断：${message}` : message);
        }
    };
}
