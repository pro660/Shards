package com.example.shards.meta;

import java.time.OffsetDateTime;
import java.util.UUID;

// 메타 DB에 저장된 재배치 작업의 한 행을 담습니다.
public record MigrationJob(
        long jobId,
        int bucketId,
        int sourceShardId,
        int targetShardId,
        long sourceMappingVersion,
        String phase,
        UUID lastCopiedOrderId,
        long copiedOrderCount,
        OffsetDateTime verifiedAt,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime finishedAt
) {
}
