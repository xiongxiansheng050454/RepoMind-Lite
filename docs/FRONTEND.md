# 前端界面使用指南

## 访问前端

启动服务后，在浏览器访问：
```
http://localhost:8080
```

## 功能说明

### 1. 提交仓库
- 在首页输入公开的 Java Git 仓库 URL（例如：`https://github.com/spring-guides/gs-rest-service`）
- 可选填入分支、标签或 commit hash（留空则使用 HEAD）
- 点击"开始索引"提交仓库

### 2. 查看索引状态
- 提交后会显示仓库列表，实时显示索引进度
- 状态包括：QUEUED（排队）→ CLONING（克隆）→ PARSING（解析）→ EMBEDDING（向量化）→ READY（就绪）
- 显示文件数、片段数和提交时间

### 3. 开始问答
- 点击状态为 READY 的仓库卡片进入问答界面
- 在输入框输入关于代码的问题
- 支持流式输出（SSE），实时显示 AI 回答
- 自动显示代码引用（文件路径和行号）

### 4. 界面特性
- 深色主题，专业技术风格
- 响应式设计，支持桌面和移动端
- 自动保存已提交的仓库到 localStorage
- 实时状态轮询和通知提示
- 字符计数和输入验证

## 技术实现

- 纯原生 JavaScript（无框架依赖）
- SSE（Server-Sent Events）流式问答
- 本地存储管理仓库列表
- RESTful API 集成

## 界面预览

界面采用深色配色方案：
- 背景色：深海军蓝 (#0B0E14)
- 主表面色：石板灰 (#1E293B)
- 强调色：天蓝 (#38BDF8) 和橙色 (#F97316)
- 文本色：明亮白 (#F8FAFC)

## API 对应关系

| 前端功能 | 后端 API |
|---------|---------|
| 提交仓库 | POST /api/repositories |
| 查询索引状态 | GET /api/repositories/{id}/index-status |
| 代码问答 | POST /api/repositories/{id}/chat |

## 浏览器兼容性

支持所有现代浏览器：
- Chrome / Edge 90+
- Firefox 88+
- Safari 14+

需要 JavaScript 和 EventSource（SSE）支持。
