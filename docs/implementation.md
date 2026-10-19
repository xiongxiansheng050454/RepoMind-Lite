# Implementation record

Approved scope: Java 17 / Spring Boot 3 / Maven single application; public HTTPS Java Git repositories; RabbitMQ asynchronous AST indexing; 1536-dimensional OpenAI-compatible embedding; PostgreSQL pgvector and keyword search; LangChain4j AI Services with repository-scoped tools; Redis cache/rate limit; POST SSE answers; Compose and meaningful tests.

## Tasks

1. Foundation — complete; Maven baseline compilation succeeded; Compose dependencies healthy.
2. Repository ingestion, indexing, and retrieval — implemented, 19 tests passed including 6 real PostgreSQL/RabbitMQ integration tests. Review identified filtered HNSW recall starvation and truncated warning totals; fixes queued for final fix round.
3. Agent tools, stream lifecycle, cache, limits — in progress.
4. Integration verification, deployment, README — pending.

## Decisions

- Existing folder has no Git metadata; work directly in the user-provided empty directory, without initializing Git or making commits.
- Ruling: use total tool invocations (not framework round trips) for the five-call bound; multi-tool responses cannot bypass the budget. Cost if wrong: stricter behavior than a five-round agent.
- Ruling: an indexed repository remains pinned to its indexed commit, including file reads and Git history, until a future explicit reindex feature. Cost if wrong: upstream changes are not reflected in MVP.
- Ruling: durable database recovery of queued/expired indexing jobs supplements RabbitMQ so application restarts cannot strand accepted work. Cost if wrong: one small recovery scheduler/table rather than best-effort publish.
- Ruling: question answering is single-turn; no conversation memory or account system. Cost if wrong: follow-up questions must include their own context.
- No credentials supplied: automated model tests use deterministic local HTTP/model fixtures, real provider acceptance can only be performed after supplying an API key.

## Interface review

| Producer / consumer | Shared contract | Review |
| --- | --- | --- |
| Schema / indexing store | repository ID, job ID, pinned commit, progress, chunks | One transactional store; Flyway migrations are source of truth. |
| Indexer / tools | work directory, exact source path and line ranges | Tools read pinned checkout only, reject symlinks and traversal. |
| Retrieval / Agent | source-bearing search hits | Citation events must refer to retrieved/read ranges. |
| Agent / SSE cache | final answer, citations, usage, finish | Cache only successful validated results, repository commit in key. |
| Foundation | dependencies / runtime | Coordinates corrected to dev.langchain4j; Java release 17. |

Verification commands and final limitations will be recorded here as work proceeds.

- Live Git smoke: `mvn -Dtest=PublicGitSmokeTest -Drepomind.live-git=true test` passed 1/1 against spring-guides/gs-rest-service on GitHub; verified custom HTTPS clone transport and AST extraction without model calls.
- Docker dependencies: PostgreSQL/pgvector, Redis and RabbitMQ all reported healthy.
