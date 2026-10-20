// Model settings: header status pill, settings modal, save and connection test
const settingsEls = {
    status: document.getElementById('llmStatus'),
    dot: document.getElementById('llmStatusDot'),
    text: document.getElementById('llmStatusText'),
    modal: document.getElementById('settingsModal'),
    form: document.getElementById('settingsForm'),
    close: document.getElementById('settingsClose'),
    baseUrl: document.getElementById('llmBaseUrl'),
    apiKey: document.getElementById('llmApiKey'),
    keyHint: document.getElementById('llmKeyHint'),
    model: document.getElementById('llmModel'),
    embeddingModel: document.getElementById('llmEmbeddingModel'),
    maxTokens: document.getElementById('llmMaxTokens'),
    results: document.getElementById('settingsTestResults'),
    test: document.getElementById('settingsTest'),
    save: document.getElementById('settingsSave')
};

let llmConfigured = null;
let lastFocus = null;

function applyLlmStatus(view) {
    llmConfigured = !!view.configured;
    settingsEls.dot.className = 'status-dot' + (llmConfigured ? '' : ' warn');
    settingsEls.text.textContent = llmConfigured ? view.model : '模型未配置';
}

async function refreshLlmStatus() {
    try {
        const response = await fetch(`${API_BASE}/settings/llm`);
        if (!response.ok) throw new Error('status ' + response.status);
        const view = await response.json();
        applyLlmStatus(view);
        return view;
    } catch (e) {
        settingsEls.dot.className = 'status-dot bad';
        settingsEls.text.textContent = '后端不可用';
        return null;
    }
}

function fillSettingsForm(view) {
    settingsEls.baseUrl.value = view.baseUrl;
    settingsEls.model.value = view.model;
    settingsEls.embeddingModel.value = view.embeddingModel;
    settingsEls.maxTokens.value = view.maxTokens;
    settingsEls.apiKey.value = '';
    settingsEls.keyHint.textContent = view.apiKeyHint ? `（已保存 ${view.apiKeyHint}，留空则不修改）` : '（必填）';
    settingsEls.apiKey.required = !view.configured;
}

async function openSettings() {
    lastFocus = document.activeElement;
    settingsEls.results.innerHTML = '';
    settingsEls.modal.hidden = false;
    const view = await refreshLlmStatus();
    if (view) fillSettingsForm(view);
    settingsEls.baseUrl.focus();
}

function closeSettings() {
    settingsEls.modal.hidden = true;
    if (lastFocus && lastFocus.focus) lastFocus.focus();
}

function settingsPayload() {
    const body = {
        baseUrl: settingsEls.baseUrl.value.trim(),
        model: settingsEls.model.value.trim(),
        embeddingModel: settingsEls.embeddingModel.value.trim(),
        maxTokens: parseInt(settingsEls.maxTokens.value, 10)
    };
    const key = settingsEls.apiKey.value.trim();
    if (key) body.apiKey = key;
    return body;
}

async function sendSettings(path, method) {
    const response = await fetch(`${API_BASE}/settings/llm${path}`, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settingsPayload())
    });
    if (!response.ok) throw await readApiError(response, '请求失败');
    return response.json();
}

function renderTestResults(result) {
    const rows = [['对话模型', result.chat], ['向量模型', result.embedding]];
    settingsEls.results.innerHTML = rows.map(([label, part]) => `
        <li class="${part.ok ? 'ok' : 'fail'}">
            <span class="label">${part.ok ? '✓' : '✕'} ${label}</span>
            <span>${escapeHtml(part.ok ? '连接正常' : describeFailure(part.code, part.message).text)}</span>
        </li>`).join('');
}

async function handleTestSettings() {
    settingsEls.test.disabled = true;
    settingsEls.results.innerHTML = '<li class="ok"><span>正在测试…</span></li>';
    try {
        renderTestResults(await sendSettings('/test', 'POST'));
    } catch (error) {
        settingsEls.results.innerHTML = `<li class="fail"><span>${escapeHtml(describeFailure(error.code, error.message).text)}</span></li>`;
    } finally {
        settingsEls.test.disabled = false;
    }
}

async function handleSaveSettings(event) {
    event.preventDefault();
    settingsEls.save.disabled = true;
    try {
        applyLlmStatus(await sendSettings('', 'PUT'));
        closeSettings();
        showToast('success', '已保存', '模型配置已生效');
    } catch (error) {
        showToast('error', '保存失败', describeFailure(error.code, error.message).text);
    } finally {
        settingsEls.save.disabled = false;
    }
}

function initSettings() {
    settingsEls.status.addEventListener('click', openSettings);
    settingsEls.close.addEventListener('click', closeSettings);
    settingsEls.test.addEventListener('click', handleTestSettings);
    settingsEls.form.addEventListener('submit', handleSaveSettings);
    settingsEls.modal.addEventListener('click', e => { if (e.target === settingsEls.modal) closeSettings(); });
    document.addEventListener('keydown', e => { if (e.key === 'Escape' && !settingsEls.modal.hidden) closeSettings(); });
    refreshLlmStatus();
}

document.addEventListener('DOMContentLoaded', initSettings);
