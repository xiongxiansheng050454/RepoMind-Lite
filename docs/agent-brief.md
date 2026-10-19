# Task 3: Agent, Redis and SSE

Implement under existing `com.repomind.lite` packages. Do not spawn agents or change indexing interfaces without notifying root.

Approved scope: POST `/api/repositories/{repositoryId}/chat` JSON `{question}` => real `text/event-stream`; LangChain4j 1.10.0 AI Services `TokenStream`, tools `search_code(query,topK)` / `read_file(path,startLine,endLine)` / `git_log(path,limit)`; five total tool invocations maximum, 30-second whole request deadline including initial retrieval/cache, bounded context/input/output; cancellation on client disconnect; verified citations; Micrometer metrics; Redis 10-minute cache and atomic IP fixed-window rate limit. Single-turn per request, no users or chat memory.

Existing contracts:
- RepositoryService.requireReady(UUID) => RepositoryRecord(id,repoUrl,ref,status,workDir,commitSha,defaultBranch).
- SearchService.search(UUID,String,int) => List<SearchHit(CodeChunk chunk,double score)>.
- CodeChunk(id,repositoryId,path,symbol,startLine,endLine,content).
- GitRepositoryService.readFile(RepositoryRecord,String,int,int) => FileExcerpt(path,startLine,endLine,content).
- GitRepositoryService.gitLog(RepositoryRecord,String,int) => List<GitCommit(sha,author,message,time)>.
- EmbeddingGateway bean configured lazily from repomind.llm.*, no duplicate model bean.
- ApiException(HttpStatus,String code,String message) and ApiExceptionAdvice provide safe JSON errors.

Implementation requirements:
- Startup must work without model key; first operation requiring model gets clear service-not-configured error.
- Same `repomind.llm.base-url/api-key/model` env config for streaming model; maxRetries=0, no request/response logs, maxTokens configurable default1024.
- Initial retrieval search topK=5 before model; seed evidence and prompt, do not count this prerequisite as Agent tool invocation.
- Tools scoped to a repository supplied server-side, never accept caller-specified repo ID or filesystem root.
- Hard total tool-call budget catches parallel tools in a response; `.maxSequentialToolsInvocations(5)` alone is insufficient. Failure should abort further model requests, not convert budget errors into repeated error tool messages.
- Scope run cancellation through streaming response context (`onPartialResponseWithContext`) or a cancellable model/client wrapper; no further tools/requests after cancellation. Whole request deadline must work even when model sends no tokens. Store native handles for immediate cancellation after first context. Cancel initial blocking work via Future and close active model stream where supported.
- Bounded question (1..2000 chars); input context budget conservative and output token budget enforced between rounds; include tools/model prompts/history in checks. Enforce tool result sizes and reserve output tokens.
- SSE `token` data object `{text}`, `citation` data `{path,startLine,endLine,explanation}`, `done` data `{status,toolCalls,durationMs,inputTokens,outputTokens,cached}`, `error` data `{code,message}`. Exactly one terminal event; no done after error; close emitter and clean scheduled timeout after success/error/disconnect. No model exception messages on wire.
- Prompt uses citation form `[path:Lstart-Lend]`. Collect citation candidates only from initial search/tool evidence and emit citations actually cited by final answer. Validate final citations against allowed ranges; if none with evidence, or hallucinated citation, emit safe error and do not cache. Evidence-less answers must say repository cannot confirm and must not fabricate sources. Intermediate tool planning prose should not be mislabeled as final answer tokens if possible; final validation can invalidate an already streamed answer with error event (document this streaming limitation).
- `git_log` provenance belongs in context; if git history answers need source citations, tie it to a read/search range rather than inventing line numbers.
- Redis cache key hash includes repository UUID+pinned commit+normalized question+model name; cache successful answer+citations+usage, TTL600sec; replay token then citation(s) then done. Redis transient failures: cache fail-open, limiter fail-closed clear503. Per-IP chat limit 10/min via Lua INCR+EXPIRE atomically; use request.getRemoteAddr (ignore untrusted forwarded headers).
- Micrometer question duration/search/tool call/token counters and SSE errors. MDC requestId + repositoryId, restore/remove after async callbacks; avoid secrets/code in logs.
- No full frontend.

Tests: real AI Services with scripted StreamingChatModel or local HTTP fixture; verify initial retrieval+tool loop+token/citation/done; ungrounded references=>error no cache; parallel six-tool requests invoke at most five; whole timeout even no partial tokens; disconnect cancels stream+future/no postcancel tools; token/context budget stops next round; cache TTL/replay and rate limiter actual Redis via Testcontainers. Unit tests first where feasible. Local Maven root approved command can be used; Java `E:/IDEA/IntelliJ IDEA/jbr`, Maven `C:/Users/xiong/.m2/wrapper/dists/apache-maven-3.9.15-bin/4rlcemksed9vjmkvgss0jpc4po/apache-maven-3.9.15/bin/mvn.cmd`, repo `C:/Users/xiong/.m2/repository`. Testcontainers Docker needs `-Dapi.version=1.44` on this host. Source reference `.cache/lc-sources` contains actual AI Services/TokenStream1.10 source.

Report implemented behavior, covering test command/result, actual limitations. Root will run full verification.
