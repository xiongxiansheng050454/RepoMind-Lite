# Task 2 review fixes

Apply after Task 3 implementation finishes. Do not change chat APIs. Add regression tests first and verify red/green against real PostgreSQL.

1. `SearchService.searchVector`: global HNSW approximate selection can post-filter away all current-repository vectors if other repositories dominate the nearest40 candidates. For the bounded20k-chunk MVP, materialize a repository-filtered (id, embedding) CTE and exact-sort its distances for vector candidates; keep keyword candidates/scoring and repository isolation. Existing HNSW index may remain for future optimized larger datasets but README must not imply current query uses ANN. Test with >100 nearer chunks in another repository and no keyword matches, force PostgreSQL index scan on old query to demonstrate failure, then ensure current repository still returns requested hits. Test normalized keyword contribution changes ordering with hand-chosen vectors/text.

2. `IndexStore` silently truncates warnings to1000 before serialization, and warningCount uses persisted list.size. Remove truncation in the bounded MVP, preserve the actual warning total/details, and test >1000 warnings in a completed job/progress persisted response. All warning messages are generated internally (paths/reasons), never provider raw errors.

3. Reset progress/files/chunks/warnings on a new claim. A retry should not display prior attempt's90% progress while CLONING. Verify firstattemptprogress+failure then retry claim shows stageCLONING/progress0/counts0.

4. Format initial indexing source/test code consistently for readability if safe. Do not add framework abstractions or change functional interfaces merely for style. New repository has no baseline compatibility/Git history to preserve. Prefer automated Google Java Format AOSP if readily available; no formatter required to finish the bug fixes.

Review output: spec compliance and quality; commands/results; no commits (no Git repository), no subagents.
