package com.repomind.lite.api;

import com.repomind.lite.application.RepositoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final RepositoryService repositories;

    public RepositoryController(RepositoryService repositories) {
        this.repositories = repositories;
    }

    public record SubmitRequest(@NotBlank @Size(max = 2048) String repoUrl, @Size(max = 255) String ref) {
    }

    @PostMapping
    public ResponseEntity<RepositoryResponses.Submission> submit(@Valid @RequestBody SubmitRequest request) {
        return ResponseEntity.accepted().body(RepositoryResponses.Submission.from(
                repositories.submit(request.repoUrl(), request.ref())));
    }

    @GetMapping("/{id}/index-status")
    public RepositoryResponses.IndexStatusResponse status(@PathVariable UUID id) {
        return RepositoryResponses.IndexStatusResponse.from(repositories.indexStatus(id));
    }
}
