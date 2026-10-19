package com.repomind.lite.infrastructure;

import com.repomind.lite.domain.CodeChunk;
import com.repomind.lite.domain.SearchHit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CodeSearchRepository {
    private final JdbcTemplate jdbc;

    public CodeSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchHit> search(UUID repositoryId, String query, int topK, float[] embedding) {
        String vector = IndexStore.vector(embedding);
        return jdbc.query("""
                WITH q AS (SELECT ?::vector AS embedding, websearch_to_tsquery('english', ?) AS terms),
                scoped AS (
                    SELECT c.*
                    FROM code_chunks c
                    WHERE c.repository_id = ?
                ),
                vector_candidates AS (
                    SELECT s.id FROM scoped s CROSS JOIN q
                    WHERE s.embedding IS NOT NULL
                    ORDER BY s.embedding <=> q.embedding
                    LIMIT 100
                ),
                keyword_candidates AS (
                    SELECT s.id FROM scoped s CROSS JOIN q
                    WHERE s.search_vector @@ q.terms
                    ORDER BY ts_rank_cd(s.search_vector, q.terms) DESC
                    LIMIT 100
                ),
                candidates AS (SELECT id FROM vector_candidates UNION SELECT id FROM keyword_candidates),
                ranked AS (
                    SELECT s.*, GREATEST(0, 1 - (s.embedding <=> q.embedding)) AS vector_score,
                           ts_rank_cd(s.search_vector, q.terms) AS keyword_score
                    FROM scoped s
                    JOIN candidates c ON c.id = s.id
                    CROSS JOIN q
                ),
                scored AS (
                    SELECT *, 0.7 * vector_score + 0.3 * COALESCE(
                        keyword_score / NULLIF(MAX(keyword_score) OVER (), 0), 0) AS score
                    FROM ranked
                )
                SELECT * FROM scored ORDER BY score DESC, path, start_line LIMIT ?
                """, (rs, n) -> new SearchHit(new CodeChunk(
                        rs.getObject("id", UUID.class), repositoryId, rs.getString("path"),
                        rs.getString("symbol"), rs.getInt("start_line"), rs.getInt("end_line"),
                        rs.getString("content")), rs.getDouble("score")),
                vector, query, repositoryId, topK);
    }
}
