package com.example.shards.meta;

import com.example.shards.sharding.BucketHasher;
import com.example.shards.sharding.ShardConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 개발 중 앱 시작 시 메타 조회와 실제 샤드 연결을 확인합니다.
@Component
public class MetaDbConnCheck implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(MetaDbConnCheck.class);

    // 기존 메타 DB용 SQL 실행 도구입니다.
    private final JdbcTemplate jdbcTemplate;

    private final BucketMappingRepository bucketMappingRepository;

    // 연결 키로 샤드용 SQL 실행 도구를 찾습니다.
    private final ShardConnectionRegistry shardConnectionRegistry;

    public MetaDbConnCheck(
            JdbcTemplate jdbcTemplate,
            BucketMappingRepository bucketMappingRepository,
            ShardConnectionRegistry shardConnectionRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.bucketMappingRepository = bucketMappingRepository;
        this.shardConnectionRegistry = shardConnectionRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {

        String databaseName = jdbcTemplate.queryForObject(
                "SELECT current_database()",
                String.class
        );

        log.info("Meta DB Connection Successful: {}", databaseName);

        // 초기 매핑에서 A와 B로 나뉘는 확인용 사용자 번호입니다.
        long[] sampleUserIds = {1L, 101L};

        for (long sampleUserId : sampleUserIds) {

            // 사용자 번호로 버킷을 계산합니다.
            int bucketId = BucketHasher.toBucketId(sampleUserId);

            // 현재 담당 샤드와 연결 키를 메타 DB에서 읽습니다.
            BucketMapping mapping =
                    bucketMappingRepository.findByBucketId(bucketId);

            // 메타 DB가 알려 준 키로 실제 연결을 선택합니다.
            JdbcTemplate shardJdbcTemplate =
                    shardConnectionRegistry.getJdbcTemplate(
                            mapping.connectionKey()
                    );

            // 선택한 샤드에 실제로 접속해서 DB 이름을 조회합니다.
            String shardDatabaseName = shardJdbcTemplate.queryForObject(
                    "SELECT current_database()",
                    String.class
            );

            log.info(
                    "Shard connection: userId={}, bucketId={}, shard={}, "
                            + "connectionKey={}, database={}, "
                            + "state={}, shardStatus={}, version={}",
                    sampleUserId,
                    mapping.bucketId(),
                    mapping.shardName(),
                    mapping.connectionKey(),
                    shardDatabaseName,
                    mapping.state(),
                    mapping.shardStatus(),
                    mapping.version()
            );
        }
    }
}