package com.repomind.lite.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.lite.errors.BusinessException;
import com.repomind.lite.application.IndexClaim;
import com.repomind.lite.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Repository
public class IndexStore {
    public record Submission(UUID repositoryId, UUID jobId, String status) {
    }

    public record IndexStatus(UUID repositoryId, UUID jobId, String status, String stage, double progress,
                              int retryCount, int filesProcessed, int chunksCreated, List<String> warnings,
                              String error, Instant startedAt, Instant completedAt) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public int warningCount() {
            return warnings.size();
        }
    }

    public com.repomind.lite.application.RepositorySubmission submitApplication(RepositoryUrlValidator.Remote remote, Path workRoot) {
        Submission result = submit(remote, workRoot);
        return new com.repomind.lite.application.RepositorySubmission(result.repositoryId(), result.jobId(), result.status());
    }

    public com.repomind.lite.application.IndexStatus statusApplication(UUID id) {
        IndexStatus result = status(id);
        return new com.repomind.lite.application.IndexStatus(result.repositoryId(), result.jobId(), result.status(), result.stage(), result.progress(),
                result.retryCount(), result.filesProcessed(), result.chunksCreated(), result.warnings(), result.error(), result.startedAt(), result.completedAt());
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public IndexStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Submission submit(RepositoryUrlValidator.Remote remote, Path workRoot) {
        return tx.execute(transaction -> {
            UUID proposed = UUID.randomUUID();
            jdbc.update("INSERT INTO repositories(id,repo_url,ref,stable_url_ref_key,work_dir) VALUES (?,?,?,?,?) ON CONFLICT(stable_url_ref_key) DO NOTHING", proposed, remote.url(), remote.ref(), remote.key(), workRoot.resolve(proposed.toString()).toAbsolutePath().normalize().toString());
            var repository = jdbc.queryForObject("SELECT * FROM repositories WHERE stable_url_ref_key=? FOR UPDATE", IndexStore::repository, remote.key());
            var existing = jdbc.query("SELECT j.id,j.stage FROM index_jobs j JOIN repositories r ON r.current_job_id=j.id WHERE r.id=?", (rs, n) -> new Submission(repository.id(), rs.getObject("id", UUID.class), rs.getString("stage")), repository.id());
            if (!existing.isEmpty() && !existing.get(0).status().equals("FAILED")) return existing.get(0);
            UUID jobId = UUID.randomUUID();
            jdbc.update("INSERT INTO index_jobs(id,repository_id,stage) VALUES (?,?,'QUEUED')", jobId, repository.id());
            jdbc.update("UPDATE repositories SET current_job_id=?,status='QUEUED',updated_at=now() WHERE id=?", jobId, repository.id());
            jdbc.update("INSERT INTO index_outbox(job_id) VALUES (?)", jobId);
            return new Submission(repository.id(), jobId, "QUEUED");
        });
    }

    public RepositoryRecord get(UUID id) {
        var rows = jdbc.query("SELECT * FROM repositories WHERE id=?", IndexStore::repository, id);
        if (rows.isEmpty())
            throw BusinessException.notFound("REPOSITORY_NOT_FOUND", "Repository was not found");
        return rows.get(0);
    }

    public IndexStatus status(UUID id) {
        get(id);
        return jdbc.queryForObject("SELECT j.*,r.status FROM index_jobs j JOIN repositories r ON r.current_job_id=j.id WHERE r.id=?", (rs, n) -> new IndexStatus(id, rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("stage"), rs.getDouble("progress"), rs.getInt("retry_count"), rs.getInt("files_processed"), rs.getInt("chunks_created"), warnings(rs.getString("warnings")), rs.getString("error"), instant(rs, "started_at"), instant(rs, "completed_at")), id);
    }

    public Optional<IndexClaim> claim(UUID jobId) {
        return tx.execute(transaction -> {
            UUID token = UUID.randomUUID();
            var jobs = jdbc.query("UPDATE index_jobs SET stage='CLONING',attempts=attempts+1,retry_count=attempts,lease_token=?,lease_until=now()+interval '90 seconds',started_at=COALESCE(started_at,now()),error=NULL,updated_at=now() WHERE id=? AND stage='QUEUED' AND attempts<4 RETURNING repository_id,attempts", (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getInt(2)}, token, jobId);
            if (jobs.isEmpty()) return Optional.empty();
            UUID repositoryId = (UUID) jobs.get(0)[0];
            jdbc.update("UPDATE repositories SET status='INDEXING',updated_at=now() WHERE id=?", repositoryId);
            return Optional.of(new IndexClaim(jobId, token, get(repositoryId), (Integer) jobs.get(0)[1]));
        });
    }

    public boolean heartbeat(IndexClaim claim) {
        return jdbc.update("UPDATE index_jobs SET lease_until=now()+interval '90 seconds',updated_at=now() WHERE id=? AND lease_token=? AND stage NOT IN ('READY','FAILED','QUEUED')", claim.jobId(), claim.token()) == 1;
    }

    public void progress(IndexClaim claim, String stage, double progress, int files, int chunks, List<String> warnings) {
        jdbc.update("UPDATE index_jobs SET stage=?,progress=?,files_processed=?,chunks_created=?,warnings=?::jsonb,updated_at=now() WHERE id=? AND lease_token=?", stage, progress, files, chunks, json(warnings), claim.jobId(), claim.token());
    }

    public boolean complete(IndexClaim claim, Path workDir, String commit, String defaultBranch, List<CodeChunk> chunks, List<float[]> vectors, List<String> warnings, int files) {
        if (chunks.size() != vectors.size()) throw new IllegalArgumentException("Embedding count mismatch");
        return Boolean.TRUE.equals(tx.execute(transaction -> {
            if (!owns(claim)) return false;
            jdbc.update("DELETE FROM code_chunks WHERE repository_id=?", claim.repository().id());
            var indices = java.util.stream.IntStream.range(0, chunks.size()).boxed().toList();
            jdbc.batchUpdate("INSERT INTO code_chunks(id,repository_id,path,symbol,start_line,end_line,content,embedding) VALUES (?,?,?,?,?,?,?,?::vector)", indices, 100, (ps, index) -> {
                var c = chunks.get(index);
                ps.setObject(1, c.id());
                ps.setObject(2, claim.repository().id());
                ps.setString(3, c.path());
                ps.setString(4, c.symbol());
                ps.setInt(5, c.startLine());
                ps.setInt(6, c.endLine());
                ps.setString(7, c.content());
                ps.setString(8, vector(vectors.get(index)));
            });
            jdbc.update("UPDATE repositories SET status='READY',work_dir=?,commit_sha=?,default_branch=?,updated_at=now() WHERE id=?", workDir.toAbsolutePath().toString(), commit, defaultBranch, claim.repository().id());
            jdbc.update("UPDATE index_jobs SET stage='READY',progress=1,files_processed=?,chunks_created=?,warnings=?::jsonb,lease_token=NULL,lease_until=NULL,completed_at=now(),updated_at=now() WHERE id=?", files, chunks.size(), json(warnings), claim.jobId());
            jdbc.update("DELETE FROM index_outbox WHERE job_id=?", claim.jobId());
            return true;
        }));
    }

    public void fail(IndexClaim claim, String safeError) {
        tx.executeWithoutResult(transaction -> {
            if (!owns(claim)) return;
            boolean terminal = claim.attempt() >= 4;
            jdbc.update("UPDATE index_jobs SET stage=?,error=?,lease_token=NULL,lease_until=NULL,completed_at=CASE WHEN ? THEN now() ELSE NULL END,updated_at=now() WHERE id=?", terminal ? "FAILED" : "QUEUED", safeError, terminal, claim.jobId());
            jdbc.update("UPDATE repositories SET status=?,updated_at=now() WHERE id=?", terminal ? "FAILED" : "QUEUED", claim.repository().id());
            if (terminal) jdbc.update("DELETE FROM index_outbox WHERE job_id=?", claim.jobId());
            else
                jdbc.update("UPDATE index_outbox SET next_publish_at=now()+interval '5 seconds' WHERE job_id=?", claim.jobId());
        });
    }

    public void recover() {
        tx.executeWithoutResult(transaction -> {
            var expired = jdbc.query("SELECT id,repository_id,attempts FROM index_jobs WHERE lease_until<now() AND stage NOT IN ('QUEUED','READY','FAILED') FOR UPDATE SKIP LOCKED", (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)});
            for (Object[] row : expired) {
                boolean terminal = (Integer) row[2] >= 4;
                jdbc.update("UPDATE index_jobs SET stage=?,lease_token=NULL,lease_until=NULL,error='Indexing interrupted; recovery attempted',completed_at=CASE WHEN ? THEN now() ELSE NULL END,updated_at=now() WHERE id=?", terminal ? "FAILED" : "QUEUED", terminal, row[0]);
                jdbc.update("UPDATE repositories SET status=?,updated_at=now() WHERE id=?", terminal ? "FAILED" : "QUEUED", row[1]);
                if (terminal) jdbc.update("DELETE FROM index_outbox WHERE job_id=?", row[0]);
            }
            jdbc.update("INSERT INTO index_outbox(job_id) SELECT id FROM index_jobs WHERE stage='QUEUED' ON CONFLICT(job_id) DO NOTHING");
        });
    }

    public List<UUID> pendingMessages() {
        return jdbc.query("SELECT o.job_id FROM index_outbox o JOIN index_jobs j ON j.id=o.job_id WHERE j.stage='QUEUED' AND o.next_publish_at<=now() ORDER BY o.next_publish_at LIMIT 100", (rs, n) -> rs.getObject(1, UUID.class));
    }

    public void published(UUID jobId) {
        jdbc.update("UPDATE index_outbox SET next_publish_at=now()+interval '30 seconds' WHERE job_id=?", jobId);
    }

    private boolean owns(IndexClaim c) {
        return !jdbc.query("SELECT id FROM index_jobs WHERE id=? AND lease_token=? AND lease_until>now() FOR UPDATE", (rs, n) -> rs.getObject(1), c.jobId(), c.token()).isEmpty();
    }

    public static String vector(float[] vector) {
        if (vector.length != 1536) throw new IllegalArgumentException("Embedding dimension must be 1536");
        StringJoiner text = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Embedding must contain finite values");
            text.add(Float.toString(value));
        }
        return text.toString();
    }

    private List<String> warnings(String value) {
        try {
            return json.readValue(value, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String json(List<String> warnings) {
        try {
            return json.writeValueAsString(warnings);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static RepositoryRecord repository(ResultSet rs, int n) throws SQLException {
        return new RepositoryRecord(rs.getObject("id", UUID.class), rs.getString("repo_url"), rs.getString("ref"), rs.getString("status"), Path.of(rs.getString("work_dir")), rs.getString("commit_sha"), rs.getString("default_branch"));
    }
}
