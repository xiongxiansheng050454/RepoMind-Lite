// API Base URL
const API_BASE = '/api';

// State Management
const state = {
    repositories: new Map(),
    currentRepositoryId: null,
    isStreaming: false,
    pollers: new Map()
};

const TERMINAL = new Set(['READY', 'FAILED']);
const POLL_INTERVAL_MS = 4000;
const POLL_LIMIT_MS = 40 * 60 * 1000;

const STAGE_LABELS = {
    QUEUED: '排队中', CONFIG: '检查配置', CLONING: '克隆仓库', PARSING: '解析代码',
    EMBEDDING: '生成向量', INDEXING: '索引中', READY: '已就绪', FAILED: '失败'
};

const ERROR_HINTS = {
    LLM_NOT_CONFIGURED: '模型尚未配置，请先设置接口地址和 API Key',
    LLM_AUTH: 'API Key 被模型服务拒绝，请检查 Key 是否正确、是否过期',
    LLM_MODEL_NOT_FOUND: '模型服务上找不到所配置的模型，请检查模型名称',
    LLM_INVALID_REQUEST: '模型服务拒绝了请求，请检查模型名称和参数是否被该服务支持',
    LLM_RATE_LIMITED: '模型服务触发限流，请稍后重试',
    LLM_TIMEOUT: '模型服务响应超时，请检查网络或稍后重试',
    LLM_UNREACHABLE: '连不上模型服务，请检查接口地址和网络',
    LLM_TLS: '与模型服务的 TLS 连接失败，请检查证书或接口地址',
    LLM_SERVER_ERROR: '模型服务内部错误，请稍后重试',
    LLM_ERROR: '调用模型服务失败',
    LLM_SETTINGS_INVALID: '配置不合法',
    LLM_SETTINGS_KEY_REQUIRED: '修改接口地址后需要重新输入 API Key',
    EMBEDDING_DIMENSION: '向量模型输出维度不是 1536，请换用 1536 维的向量模型',
    GIT_DNS_FAILED: '无法解析仓库域名，请检查网络、DNS 或加速器',
    GIT_UNREACHABLE: '连不上仓库所在主机，请检查网络或加速器',
    GIT_HOST_PRIVATE_IP: '仓库域名解析到了非公网地址（若是加速器所致，请开启 SKIP_IP_VALIDATION）',
    GIT_TLS: '与仓库主机的 TLS 握手失败，Java 可能不信任加速器的证书',
    GIT_REPO_NOT_FOUND: '仓库不存在或不是公开仓库',
    GIT_REF_NOT_FOUND: '找不到指定的分支、标签或 Commit',
    GIT_REDIRECT: '仓库主机返回了重定向，当前不允许',
    GIT_TOO_LARGE: '仓库超过大小或文件数量限制',
    GIT_CLONE_FAILED: '克隆仓库失败',
    PARSE_FAILED: '解析仓库代码失败',
    INDEX_TIMEOUT: '索引耗时过长或任务租约过期',
    INDEX_INTERRUPTED: '索引被中断，系统已尝试恢复',
    INDEX_FAILED: '索引失败',
    REPOSITORY_NOT_FOUND: '后端已没有这条仓库记录，请重新提交',
    INVALID_REQUEST: '请求参数不合法'
};

async function readApiError(response, fallback) {
    let body = null;
    try { body = await response.json(); } catch (e) { /* non-JSON body */ }
    const error = new Error((body && body.message) || fallback);
    error.code = body && body.code;
    return error;
}

function describeFailure(code, message) {
    const hint = ERROR_HINTS[code];
    if (!hint) return { text: message || '未知错误', detail: '' };
    const detail = message && message !== hint && /[一-龥]/.test(message) === false ? message : '';
    return { text: hint, detail };
}

function isLlmCode(code) {
    return typeof code === 'string' && (code.startsWith('LLM_') || code === 'EMBEDDING_DIMENSION');
}

// DOM Elements
const elements = {
    submitForm: document.getElementById('submitForm'),
    submitBtn: document.getElementById('submitBtn'),
    repoUrlInput: document.getElementById('repoUrl'),
    refInput: document.getElementById('ref'),
    submitSection: document.getElementById('submitSection'),
    repositoriesSection: document.getElementById('repositoriesSection'),
    repositoryList: document.getElementById('repositoryList'),
    chatSection: document.getElementById('chatSection'),
    chatMessages: document.getElementById('chatMessages'),
    chatForm: document.getElementById('chatForm'),
    chatInput: document.getElementById('chatInput'),
    sendBtn: document.getElementById('sendBtn'),
    backBtn: document.getElementById('backBtn'),
    chatRepositoryInfo: document.getElementById('chatRepositoryInfo'),
    charCount: document.getElementById('charCount'),
    toastContainer: document.getElementById('toastContainer')
};

// Initialize App
function init() {
    setupEventListeners();
    loadRepositories();
    state.repositories.forEach((repo, id) => pollRepositoryStatus(id));
}

// Event Listeners
function setupEventListeners() {
    elements.submitForm.addEventListener('submit', handleSubmitRepository);
    elements.chatForm.addEventListener('submit', handleSendMessage);
    elements.backBtn.addEventListener('click', handleBackToList);
    elements.chatInput.addEventListener('input', handleChatInputChange);
    elements.repositoryList.addEventListener('click', handleRepositoryListClick);

    // Auto-resize textarea
    elements.chatInput.addEventListener('input', function() {
        this.style.height = 'auto';
        this.style.height = Math.min(this.scrollHeight, 150) + 'px';
    });
}

// Load Repositories from localStorage
function loadRepositories() {
    const stored = localStorage.getItem('repomind_repositories');
    if (stored) {
        try {
            const repos = JSON.parse(stored);
            repos.forEach(repo => {
                state.repositories.set(repo.repositoryId, repo);
            });
            if (state.repositories.size > 0) {
                renderRepositoryList();
            }
        } catch (e) {
            console.error('Failed to load repositories', e);
        }
    }
}

// Save Repositories to localStorage
function saveRepositories() {
    const repos = Array.from(state.repositories.values());
    localStorage.setItem('repomind_repositories', JSON.stringify(repos));
}

// Handle Submit Repository
async function handleSubmitRepository(e) {
    e.preventDefault();

    const repoUrl = elements.repoUrlInput.value.trim();
    const ref = elements.refInput.value.trim() || null;

    setButtonLoading(elements.submitBtn, true);
    try {
        if (await submitRepository(repoUrl, ref)) elements.submitForm.reset();
    } finally {
        setButtonLoading(elements.submitBtn, false);
    }
}

async function submitRepository(repoUrl, ref) {
    try {
        const response = await fetch(`${API_BASE}/repositories`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ repoUrl, ref })
        });
        if (!response.ok) throw await readApiError(response, '提交失败');

        const data = await response.json();
        state.repositories.set(data.repositoryId, {
            repositoryId: data.repositoryId,
            jobId: data.jobId,
            repoUrl: repoUrl,
            ref: ref || 'HEAD',
            status: data.status,
            error: null,
            errorCode: null,
            failedStage: null,
            submittedAt: new Date().toISOString()
        });
        saveRepositories();
        renderRepositoryList();
        showToast('success', '提交成功', '仓库已开始索引');
        pollRepositoryStatus(data.repositoryId);
        return true;
    } catch (error) {
        showToast('error', '提交失败', describeFailure(error.code, error.message).text);
        if (error.code === 'LLM_NOT_CONFIGURED' && typeof openSettings === 'function') openSettings();
        return false;
    }
}

// Poll Repository Status
function pollRepositoryStatus(repositoryId) {
    if (state.pollers.has(repositoryId)) return;
    state.pollers.set(repositoryId, true);
    const startedAt = Date.now();
    const finish = () => state.pollers.delete(repositoryId);

    const poll = async () => {
        const repo = state.repositories.get(repositoryId);
        if (!repo) return finish();
        const wasActive = !TERMINAL.has(repo.status);
        let delay = POLL_INTERVAL_MS;
        try {
            const response = await fetch(`${API_BASE}/repositories/${repositoryId}/index-status`);
            if (response.status === 404) {
                Object.assign(repo, { status: 'FAILED', errorCode: 'REPOSITORY_NOT_FOUND', error: null, failedStage: null });
                persistAndRender();
                return finish();
            }
            if (!response.ok) throw await readApiError(response, '获取状态失败');
            applyStatus(repo, await response.json());
            persistAndRender();
            if (TERMINAL.has(repo.status)) {
                finish();
                if (wasActive) notifyFinished(repo);
                return;
            }
        } catch (error) {
            console.error('Status poll error:', error);
            delay = 8000;
        }
        if (Date.now() - startedAt > POLL_LIMIT_MS) {
            finish();
            showToast('error', '状态更新已停止', '长时间没有结果，请刷新页面或重新提交');
            return;
        }
        setTimeout(poll, delay);
    };

    poll();
}

function applyStatus(repo, data) {
    Object.assign(repo, {
        status: data.status,
        stage: data.stage,
        progress: data.progress,
        fileCount: data.filesProcessed,
        chunkCount: data.chunksCreated,
        error: data.error || null,
        errorCode: data.errorCode || null,
        failedStage: data.failedStage || null
    });
}

function persistAndRender() {
    saveRepositories();
    if (elements.chatSection.style.display === 'none') renderRepositoryList();
}

function notifyFinished(repo) {
    if (repo.status === 'READY') {
        showToast('success', '索引完成', `${repo.repoUrl} 已就绪，可以开始问答`);
        return;
    }
    showToast('error', '索引失败', describeFailure(repo.errorCode, repo.error).text);
}

function statusLabel(repo) {
    if (repo.status === 'INDEXING') return STAGE_LABELS[repo.stage] || STAGE_LABELS.INDEXING;
    return STAGE_LABELS[repo.status] || repo.status;
}

// Render Repository List
function renderRepositoryList() {
    if (state.repositories.size === 0) {
        elements.repositoriesSection.style.display = 'none';
        return;
    }

    elements.repositoriesSection.style.display = 'block';

    const repos = Array.from(state.repositories.values())
        .sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));

    elements.repositoryList.innerHTML = repos.map(repositoryCardHtml).join('');
}

function repositoryCardHtml(repo) {
    const status = String(repo.status || '').toLowerCase();
    const failed = repo.status === 'FAILED';
    const active = !TERMINAL.has(repo.status);
    const progress = progressPercent(repo);
    const mark = repo.status === 'READY' ? '✓ ' : failed ? '✕ ' : '';

    let failure = '';
    if (failed) {
        const info = describeFailure(repo.errorCode, repo.error);
        const stage = repo.failedStage ? (STAGE_LABELS[repo.failedStage] || repo.failedStage) : '';
        failure = `
            <div class="repository-error">
                ${stage ? `<strong>${escapeHtml(stage)}阶段失败：</strong>` : ''}${escapeHtml(info.text)}
                ${info.detail ? `<div class="detail">${escapeHtml(info.detail)}</div>` : ''}
            </div>`;
    }

    const actions = `
            <div class="repository-actions">
                ${failed ? '<button type="button" class="btn-small" data-action="retry">重试</button>' : ''}
                ${failed && isLlmCode(repo.errorCode) ? '<button type="button" class="btn-small" data-action="settings">配置模型</button>' : ''}
                <button type="button" class="btn-small btn-danger" data-action="delete">删除</button>
            </div>`;

    return `
        <div class="repository-card ${failed ? 'failed' : ''}" data-id="${escapeAttr(repo.repositoryId)}">
            <div class="repository-card-header">
                <div class="repository-url">${escapeHtml(repo.repoUrl)}</div>
                <div class="repository-status status-${escapeAttr(status)}">${mark}${escapeHtml(statusLabel(repo))}</div>
            </div>
            <div class="repository-meta">
                <span>📌 ${escapeHtml(repo.ref || 'HEAD')}</span>
                ${repo.fileCount ? `<span>📄 ${Number(repo.fileCount)} 文件</span>` : ''}
                ${repo.chunkCount ? `<span>🔍 ${Number(repo.chunkCount)} 片段</span>` : ''}
                <span>⏱ ${formatDate(repo.submittedAt)}</span>
            </div>
            ${active ? `<div class="progress-bar"><div style="width:${progress}%"></div></div>` : ''}
            ${failure}
            ${actions}
        </div>`;
}

// Handle Repository Click
function handleRepositoryListClick(event) {
    const card = event.target.closest('.repository-card');
    if (!card) return;
    const repo = state.repositories.get(card.dataset.id);
    if (!repo) return;

    const action = event.target.closest('[data-action]')?.dataset.action;
    if (action === 'retry') {
        submitRepository(repo.repoUrl, repo.ref === 'HEAD' ? null : repo.ref);
    } else if (action === 'settings') {
        openSettings();
    } else if (action === 'delete') {
        deleteRepository(repo);
    } else {
        openRepository(repo);
    }
}

async function deleteRepository(repo) {
    const active = !TERMINAL.has(repo.status);
    const warning = active ? '\n\n该仓库正在索引，删除会中止索引。' : '';
    if (!window.confirm(`确定删除这个仓库吗？\n${repo.repoUrl}\n\n索引数据和本地代码副本都会被删除，且无法恢复。${warning}`)) return;

    try {
        const response = await fetch(`${API_BASE}/repositories/${encodeURIComponent(repo.repositoryId)}`, { method: 'DELETE' });
        if (!response.ok && response.status !== 404) throw await readApiError(response, '删除失败');
        state.repositories.delete(repo.repositoryId);
        saveRepositories();
        renderRepositoryList();
        showToast('success', '已删除', repo.repoUrl);
    } catch (error) {
        showToast('error', '删除失败', describeFailure(error.code, error.message).text);
    }
}

function openRepository(repo) {
    if (repo.status === 'READY') {
        state.currentRepositoryId = repo.repositoryId;
        showChatSection(repo);
        return;
    }
    if (repo.status === 'FAILED') {
        const info = describeFailure(repo.errorCode, repo.error);
        showToast('error', '索引失败，无法问答', info.text);
        if (isLlmCode(repo.errorCode) && typeof openSettings === 'function') openSettings();
        return;
    }
    const progress = progressPercent(repo);
    showToast('info', '索引进行中', `${statusLabel(repo)}${progress ? `（${progress}%）` : ''}，请等待完成后再问答`);
}

function progressPercent(repo) {
    return Math.max(0, Math.min(100, Math.round((Number(repo.progress) || 0) * 100)));
}

// Show Chat Section
function showChatSection(repo) {
    elements.submitSection.style.display = 'none';
    elements.repositoriesSection.style.display = 'none';
    elements.chatSection.style.display = 'flex';

    elements.chatRepositoryInfo.textContent = `${repo.repoUrl} @ ${repo.ref}`;
    elements.chatMessages.innerHTML = '';
    elements.chatInput.value = '';
    elements.chatInput.focus();
}

// Handle Back to List
function handleBackToList() {
    elements.chatSection.style.display = 'none';
    elements.submitSection.style.display = 'block';
    state.currentRepositoryId = null;
    renderRepositoryList();
}

// Handle Send Message
async function handleSendMessage(e) {
    e.preventDefault();

    if (state.isStreaming) return;

    const question = elements.chatInput.value.trim();
    if (!question || !state.currentRepositoryId) return;

    // Add user message
    addMessage('user', question);

    // Clear input
    elements.chatInput.value = '';
    elements.chatInput.style.height = 'auto';
    elements.charCount.textContent = '0';

    // Disable input
    state.isStreaming = true;
    elements.chatInput.disabled = true;
    elements.sendBtn.disabled = true;

    // Create assistant message container
    const view = createAssistantView(addMessage('assistant', ''));

    try {
        await streamChat(state.currentRepositoryId, question, view);
    } catch (error) {
        const info = describeFailure(error.code, error.message);
        view.fail(info.text);
        showToast('error', '问答失败', info.text);
        if (isLlmCode(error.code) && typeof openSettings === 'function') openSettings();
    } finally {
        state.isStreaming = false;
        elements.chatInput.disabled = false;
        elements.sendBtn.disabled = false;
        elements.chatInput.focus();
    }
}

// Stream Chat with SSE
async function streamChat(repositoryId, question, view) {
    const response = await fetch(`${API_BASE}/repositories/${repositoryId}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question })
    });

    if (!response.ok) throw await readApiError(response, '请求失败');

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    const citations = [];
    let buffer = '';
    let eventName = 'message';
    let streamError = null;
    let finished = false;

    const handlers = {
        status: payload => view.status(payload.detail),
        thinking: payload => view.thinking(payload.text || ''),
        tool_start: payload => view.toolStart(payload),
        tool_end: payload => view.toolEnd(payload),
        token: payload => view.token(payload.text || ''),
        citation: payload => citations.push(payload),
        done: payload => { view.citations(citations); view.finish(payload); finished = true; },
        error: payload => { streamError = Object.assign(new Error(payload.message || '处理出错'), { code: payload.code }); }
    };

    const handleEvent = (name, data) => {
        let payload;
        try {
            payload = JSON.parse(data);
        } catch (e) {
            console.error('Parse SSE error:', e, data);
            return;
        }
        if (handlers[name]) handlers[name](payload);
    };

    while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const rawLine of lines) {
            const line = rawLine.replace(/\r$/, '');
            if (!line) {
                eventName = 'message';
            } else if (line.startsWith('event:')) {
                eventName = line.substring(6).trim();
            } else if (line.startsWith('data:')) {
                const data = line.substring(5).trim();
                if (data) handleEvent(eventName, data);
            }
        }
    }

    if (streamError) throw streamError;
    if (!finished) { view.citations(citations); view.finish(null); }
}

// Add Message to Chat
let messageSequence = 0;

function addMessage(role, content) {
    const messageEl = document.createElement('div');
    messageEl.className = `message ${role}`;
    messageEl.dataset.id = `m${++messageSequence}`;

    messageEl.innerHTML = `
        <div class="message-header">
            <span class="message-role ${role}">${role === 'user' ? '你' : 'RepoMind'}</span>
        </div>
        <div class="message-content" data-content>${escapeHtml(content)}</div>
    `;

    elements.chatMessages.appendChild(messageEl);
    elements.chatMessages.scrollTop = elements.chatMessages.scrollHeight;

    return messageEl;
}

// Handle Chat Input Change
function handleChatInputChange(e) {
    const length = e.target.value.length;
    elements.charCount.textContent = length;
}

// Set Button Loading State
function setButtonLoading(button, loading) {
    button.disabled = loading;
    const textEl = button.querySelector('.btn-text');
    if (textEl) {
        textEl.textContent = loading ? '处理中...' : '开始索引';
    }
}

// Show Toast Notification
function showToast(type, title, message) {
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;

    const icons = {
        success: '✓',
        error: '✕',
        info: 'ℹ'
    };

    toast.innerHTML = `
        <div class="toast-icon">${icons[type]}</div>
        <div class="toast-content">
            <div class="toast-title">${escapeHtml(title)}</div>
            <div class="toast-message">${escapeHtml(message)}</div>
        </div>
    `;

    elements.toastContainer.appendChild(toast);

    setTimeout(() => {
        toast.style.animation = 'slideIn 0.3s ease-out reverse';
        setTimeout(() => toast.remove(), 300);
    }, type === 'error' ? 9000 : 5000);
}

// Utility: Escape HTML
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text == null ? '' : String(text);
    return div.innerHTML;
}

function escapeAttr(text) {
    return escapeHtml(text).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// Utility: Format Date
function formatDate(dateStr) {
    const date = new Date(dateStr);
    const now = new Date();
    const diff = now - date;
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return '刚刚';
    if (minutes < 60) return `${minutes} 分钟前`;
    if (hours < 24) return `${hours} 小时前`;
    if (days < 7) return `${days} 天前`;

    return date.toLocaleDateString('zh-CN');
}

// Start App
document.addEventListener('DOMContentLoaded', init);
