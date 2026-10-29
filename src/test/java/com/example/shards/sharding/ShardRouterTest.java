package com.example.shards.sharding;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShardRouterTest {

    // 실제 DB 대신 원하는 조회 결과를 돌려줄 테스트용 객체입니다.
    private final BucketMappingRepository repository =
            mock(BucketMappingRepository.class);

    private final ShardConnectionRegistry registry =
            mock(ShardConnectionRegistry.class);

    // 라우터 자체는 실제 구현을 사용합니다.
    private final ShardRouter router =
            new ShardRouter(repository, registry);

    @Test
    void routesToActiveShard() {
        BucketMapping mapping = sampleMapping("NORMAL", "ACTIVE");
        JdbcTemplate shardB = mock(JdbcTemplate.class);

        // 사용자 101의 버킷은 10입니다.
        when(repository.findByBucketId(10)).thenReturn(mapping);
        when(registry.getJdbcTemplate("shardB")).thenReturn(shardB);

        ShardRoute route = router.route(101L);

        // 조회한 매핑과 선택한 실행 도구가 전달되는지 확인합니다.
        assertEquals(mapping, route.mapping());
        assertSame(shardB, route.jdbcTemplate());
    }

    @Test
    void rejectsInactiveShard() {
        when(repository.findByBucketId(10))
                .thenReturn(sampleMapping("NORMAL", "INACTIVE"));

        assertThrows(
                IllegalStateException.class,
                () -> router.route(101L)
        );

        // 거부한 요청은 연결 선택 단계까지 진행하면 안 됩니다.
        verifyNoInteractions(registry);
    }

    @Test
    void rejectsMigratingBucket() {
        when(repository.findByBucketId(10))
                .thenReturn(sampleMapping("MIGRATING", "ACTIVE"));

        assertThrows(
                IllegalStateException.class,
                () -> router.route(101L)
        );

        verifyNoInteractions(registry);
    }

    // 테스트에서 사용할 매핑을 만듭니다. 실제 DB는 변경하지 않습니다.
    private BucketMapping sampleMapping(String state, String shardStatus) {
        return new BucketMapping(
                10,
                2,
                "B",
                "shardB",
                state,
                1L,
                shardStatus
        );
    }
}