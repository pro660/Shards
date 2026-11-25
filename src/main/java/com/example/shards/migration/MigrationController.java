package com.example.shards.migration;

import java.net.URI;

import com.example.shards.admin.AdminAccess;
import com.example.shards.meta.MigrationJob;
import com.example.shards.meta.MigrationJobRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/migrations")
public class MigrationController {

    private final AdminAccess access;
    private final MigrationJobService service;
    private final MigrationJobRepository jobs;
    private final MigrationRunner runner;

    public MigrationController(
            AdminAccess access, MigrationJobService service, MigrationJobRepository jobs, MigrationRunner runner
    ) {
        this.access = access;
        this.service = service;
        this.jobs = jobs;
        this.runner = runner;
    }

    @PostMapping
    public ResponseEntity<MigrationJob> register(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String token,
            @RequestBody Registration request
    ) {
        access.requireWrite(token);
        if (request.bucketId() == null || request.targetShardId() == null) {
            throw new IllegalArgumentException("bucketId와 targetShardId가 필요합니다.");
        }
        MigrationJob job = service.register(request.bucketId(), request.targetShardId());
        return ResponseEntity.created(URI.create("/admin/migrations/" + job.jobId())).body(job);
    }

    @GetMapping("/{jobId}")
    public MigrationJob findById(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String token,
            @PathVariable("jobId") long jobId
    ) {
        access.requireRead(token);
        return jobs.findById(jobId).orElseThrow(MigrationJobNotFoundException::new);
    }

    @GetMapping("/unfinished")
    public ResponseEntity<MigrationJob> findUnfinished(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String token
    ) {
        access.requireRead(token);
        return jobs.findUnfinished().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{jobId}/advance")
    public MigrationJob advance(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String token,
            @PathVariable("jobId") long jobId
    ) {
        access.requireWrite(token);
        return runner.advance(jobId);
    }

    public record Registration(Integer bucketId, Integer targetShardId) {
    }
}
