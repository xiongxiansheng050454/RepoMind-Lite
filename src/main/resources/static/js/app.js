// API Base URL
const API_BASE = '/api';

// State Management
const state = {
    repositories: new Map(),
    currentRepositoryId: null,
    isStreaming: false
};

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
}

// Event Listeners
function setupEventListeners() {
    elements.submitForm.addEventListener('submit', handleSubmitRepository);
    elements.chatForm.addEventListener('submit', handleSendMessage);
    elements.backBtn.addEventListener('click', handleBackToList);
    elements.chatInput.addEventListener('input', handleChatInputChange);

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
        const response = await fetch(`${API_BASE}/repositories`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ repoUrl, ref })
        });

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.message || '提交失败');
        }

        const data = await response.json();

        // Add to state
        state.repositories.set(data.repositoryId, {
            repositoryId: data.repositoryId,
            jobId: data.jobId,
            repoUrl: repoUrl,
            ref: ref || 'HEAD',
            status: data.status,
            submittedAt: new Date().toISOString()
        });

        saveRepositories();
        renderRepositoryList();

        // Clear form
        elements.submitForm.reset();

        showToast('success', '提交成功', '仓库已开始索引');

        // Start polling status
        pollRepositoryStatus(data.repositoryId);

    } catch (error) {
        showToast('error', '提交失败', error.message);
    } finally {
        setButtonLoading(elements.submitBtn, false);
    }
}

// Poll Repository Status
async function pollRepositoryStatus(repositoryId) {
    const maxAttempts = 60; // 5 minutes
    let attempts = 0;

    const poll = async () => {
        try {
            const response = await fetch(`${API_BASE}/repositories/${repositoryId}/index-status`);

            if (!response.ok) {
                throw new Error('获取状态失败');
            }

            const data = await response.json();

            // Update state
            const repo = state.repositories.get(repositoryId);
            if (repo) {
                Object.assign(repo, {
                    status: data.status,
                    phase: data.phase,
                    progress: data.progress,
                    fileCount: data.fileCount,
                    chunkCount: data.chunkCount
                });
                saveRepositories();

                // Update UI if list is visible
                if (!elements.repositoriesSection.style.display ||
                    elements.repositoriesSection.style.display !== 'none') {
                    renderRepositoryList();
                }
            }

            // Continue polling if not terminal state
            if (data.status !== 'READY' && data.status !== 'FAILED' && attempts < maxAttempts) {
                attempts++;
                setTimeout(poll, 5000);
            } else if (data.status === 'READY') {
                showToast('success', '索引完成', `仓库 ${repo.repoUrl} 已就绪`);
            } else if (data.status === 'FAILED') {
                showToast('error', '索引失败', data.error || '请重试');
            }

        } catch (error) {
            console.error('Status poll error:', error);
            if (attempts < maxAttempts) {
                attempts++;
                setTimeout(poll, 5000);
            }
        }
    };

    poll();
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

    elements.repositoryList.innerHTML = repos.map(repo => `
        <div class="repository-card" data-id="${repo.repositoryId}"
             onclick="handleRepositoryClick('${repo.repositoryId}')">
            <div class="repository-card-header">
                <div class="repository-url">${escapeHtml(repo.repoUrl)}</div>
                <div class="repository-status status-${repo.status.toLowerCase()}">
                    ${repo.status === 'READY' ? '✓' : ''} ${repo.status}
                </div>
            </div>
            <div class="repository-meta">
                <span>📌 ${repo.ref || 'HEAD'}</span>
                ${repo.fileCount ? `<span>📄 ${repo.fileCount} 文件</span>` : ''}
                ${repo.chunkCount ? `<span>🔍 ${repo.chunkCount} 片段</span>` : ''}
                <span>⏱ ${formatDate(repo.submittedAt)}</span>
            </div>
        </div>
    `).join('');
}

// Handle Repository Click
window.handleRepositoryClick = function(repositoryId) {
    const repo = state.repositories.get(repositoryId);
    if (!repo) return;

    if (repo.status !== 'READY') {
        showToast('info', '索引进行中', '请等待索引完成后再开始问答');
        return;
    }

    state.currentRepositoryId = repositoryId;
    showChatSection(repo);
};

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
    elements.repositoriesSection.style.display = state.repositories.size > 0 ? 'block' : 'none';
    state.currentRepositoryId = null;
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
    const messageId = Date.now();
    addMessage('assistant', '', messageId, true);

    try {
        await streamChat(state.currentRepositoryId, question, messageId);
    } catch (error) {
        updateMessage(messageId, `错误: ${error.message}`);
        showToast('error', '问答失败', error.message);
    } finally {
        state.isStreaming = false;
        elements.chatInput.disabled = false;
        elements.sendBtn.disabled = false;
        removeLoadingIndicator(messageId);
        elements.chatInput.focus();
    }
}

// Stream Chat with SSE
async function streamChat(repositoryId, question, messageId) {
    const response = await fetch(`${API_BASE}/repositories/${repositoryId}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question })
    });

    if (!response.ok) {
        const error = await response.json();
        throw new Error(error.message || '请求失败');
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let content = '';
    const citations = [];

    while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
            if (!line.trim() || !line.startsWith('data:')) continue;

            const data = line.substring(5).trim();
            if (!data) continue;

            try {
                const event = JSON.parse(data);

                if (event.type === 'token') {
                    content += event.content;
                    updateMessage(messageId, content, citations);
                } else if (event.type === 'citation') {
                    citations.push(event);
                    updateMessage(messageId, content, citations);
                } else if (event.type === 'done') {
                    console.log('Chat completed:', event);
                } else if (event.type === 'error') {
                    throw new Error(event.message || '处理出错');
                }
            } catch (e) {
                console.error('Parse SSE error:', e, data);
            }
        }
    }

    if (!content) {
        updateMessage(messageId, '未收到回答');
    }
}

// Add Message to Chat
function addMessage(role, content, id = null, loading = false) {
    const messageId = id || Date.now();
    const messageEl = document.createElement('div');
    messageEl.className = `message ${role}`;
    messageEl.dataset.id = messageId;

    messageEl.innerHTML = `
        <div class="message-header">
            <span class="message-role ${role}">${role === 'user' ? '你' : 'RepoMind'}</span>
        </div>
        <div class="message-content" data-content>
            ${escapeHtml(content)}
            ${loading ? '<div class="message-loading"><div class="loading-dots"><div class="loading-dot"></div><div class="loading-dot"></div><div class="loading-dot"></div></div></div>' : ''}
        </div>
    `;

    elements.chatMessages.appendChild(messageEl);
    elements.chatMessages.scrollTop = elements.chatMessages.scrollHeight;

    return messageId;
}

// Update Message Content
function updateMessage(messageId, content, citations = []) {
    const messageEl = elements.chatMessages.querySelector(`[data-id="${messageId}"]`);
    if (!messageEl) return;

    const contentEl = messageEl.querySelector('[data-content]');

    let html = escapeHtml(content).replace(/\n/g, '<br>');

    if (citations.length > 0) {
        html += `
            <div class="citations">
                <div class="citations-title">📎 代码引用</div>
                <div class="citation-list">
                    ${citations.map(c => `
                        <div class="citation-item">
                            <div class="citation-path">${escapeHtml(c.path)} [L${c.startLine}-L${c.endLine}]</div>
                            ${c.description ? `<div class="citation-desc">${escapeHtml(c.description)}</div>` : ''}
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    }

    contentEl.innerHTML = html;
    elements.chatMessages.scrollTop = elements.chatMessages.scrollHeight;
}

// Remove Loading Indicator
function removeLoadingIndicator(messageId) {
    const messageEl = elements.chatMessages.querySelector(`[data-id="${messageId}"]`);
    if (!messageEl) return;

    const loadingEl = messageEl.querySelector('.message-loading');
    if (loadingEl) {
        loadingEl.remove();
    }
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
    }, 5000);
}

// Utility: Escape HTML
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
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
