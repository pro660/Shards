package com.example.shards.sharding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 사용자 ID를 버킷으로 바꾸는 규칙을 검사합니다.
class BucketHasherTest {

    @Test
    void returnsExpectedBuckets() {
        // 미리 확인한 정답과 실제 계산 결과를 비교합니다.
        assertEquals(3, BucketHasher.toBucketId(1L));
        assertEquals(10, BucketHasher.toBucketId(2L));
        assertEquals(10, BucketHasher.toBucketId(101L));

        // long으로 표현할 수 있는 가장 큰 사용자 ID도 확인합니다.
        assertEquals(0, BucketHasher.toBucketId(Long.MAX_VALUE));
    }

    @Test
    void returnsSameBucketForRepeatedCalls() {
        // 같은 사용자 번호를 100번 계산해도 결과가 같아야 합니다.
        for (int attempt = 0; attempt < 100; attempt++) {
            assertEquals(10, BucketHasher.toBucketId(101L));
        }
    }

    @Test
    void returnsBucketWithinRange() {
        // 사용자 1번부터 1000번까지 계산 결과의 범위를 검사합니다.
        for (long userId = 1; userId <= 1000; userId++) {
            int bucketId = BucketHasher.toBucketId(userId);

            // 범위를 벗어나면 어떤 사용자에서 실패했는지도 보여 줍니다.
            assertTrue(
                    bucketId >= 0 && bucketId < 16,
                    "버킷 범위를 벗어난 사용자 ID: " + userId
            );
        }
    }

    @Test
    void rejectsZeroUserId() {
        // 0을 전달했을 때 지정한 예외가 발생해야 통과합니다.
        assertThrows(
                IllegalArgumentException.class,
                () -> BucketHasher.toBucketId(0L)
        );
    }
}