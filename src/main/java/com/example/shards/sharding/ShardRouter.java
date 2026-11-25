package com.example.shards.sharding;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import com.example.shards.meta.OperationBusyException;
import com.example.shards.meta.OperationLocks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// 사용자 번호로 주문을 처리할 샤드를 선택합니다.
@Service
public class ShardRouter {

    private final BucketMappingRepository bucketMappingRepository;
    private final ShardConnectionRegistry shardConnectionRegistry;
    private final OperationLocks locks;

    public ShardRouter(
            BucketMappingRepository bucketMappingRepository,
            ShardConnectionRegistry shardConnectionRegistry,
            OperationLocks locks
    ) {
        this.bucketMappingRepository = bucketMappingRepository;
        this.shardConnectionRegistry = shardConnectionRegistry;
        this.locks = locks;
    }

    public ShardRoute route(long userId) {

        // 사용자 번호가 잘못됐다면 해시 함수에서 예외가 발생합니다.
        int bucketId = BucketHasher.toBucketId(userId);
        locks.allowWrite(bucketId);

        // 호출할 때마다 현재 매핑을 메타 DB에서 조회합니다.
        BucketMapping mapping =
                bucketMappingRepository.findByBucketId(bucketId);

        // NORMAL인 버킷만 허용합니다.
        if (!"NORMAL".equals(mapping.state())) {
            throw new OperationBusyException(
                    "현재 요청을 처리할 수 없는 버킷입니다: " + bucketId
            );
        }

        return resolve(mapping);
    }

    public ShardRoute routeForRead(long userId) {
        int bucketId = BucketHasher.toBucketId(userId);
        locks.allowRead(bucketId);
        return resolve(bucketMappingRepository.findByBucketId(bucketId));
    }

    private ShardRoute resolve(BucketMapping mapping) {
        // 사용이 허용된 샤드인지 확인합니다.
        if (!"ACTIVE".equals(mapping.shardStatus())) {
            throw new IllegalStateException(
                    "사용할 수 없는 샤드입니다: " + mapping.shardName()
            );
        }

        // 두 상태 검사를 모두 통과한 뒤 연결을 선택합니다.
        JdbcTemplate jdbcTemplate =
                shardConnectionRegistry.getJdbcTemplate(
                        mapping.connectionKey()
                );

        return new ShardRoute(mapping, jdbcTemplate);
    }
}
