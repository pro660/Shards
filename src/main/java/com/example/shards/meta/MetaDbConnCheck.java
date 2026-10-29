package com.example.shards.meta;

import com.example.shards.sharding.BucketHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 앱 시작 시 메타 DB 연결과 매핑 조회를 확인합니다.
@Component
public class MetaDbConnCheck implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(MetaDbConnCheck.class);

    private final JdbcTemplate jdbcTemplate;

    // 버킷 매핑을 조회할 담당 객체입니다.
    private final BucketMappingRepository bucketMappingRepository;

    // Spring이 준비한 두 객체를 생성자로 전달받습니다.
    public MetaDbConnCheck(
            JdbcTemplate jdbcTemplate,
            BucketMappingRepository bucketMappingRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.bucketMappingRepository = bucketMappingRepository;
    }

    @Override
    public void run(ApplicationArguments args) {

        // 기존 메타 DB 연결 확인입니다.
        String databaseName = jdbcTemplate.queryForObject(
                "SELECT current_database()",
                String.class
        );

        log.info("Meta DB Connection Successful: {}", databaseName);

        // 이번 단계의 동작 확인용 사용자 번호입니다.
        // 실제 API에서는 요청으로 받은 사용자 번호를 사용하게 됩니다.
        long sampleUserId = 101L;

        // 사용자 번호를 버킷 번호로 바꿉니다.
        int bucketId = BucketHasher.toBucketId(sampleUserId);

        // 계산한 버킷의 현재 담당 샤드를 메타 DB에서 조회합니다.
        BucketMapping mapping =
                bucketMappingRepository.findByBucketId(bucketId);

        // 조회 결과만 출력합니다. 샤드 B에 접속하는 코드는 아직 없습니다.
        log.info(
                "Bucket mapping: userId={}, bucketId={}, shard={}, "
                        + "connectionKey={}, state={}, shardStatus={}, version={}",
                sampleUserId,
                mapping.bucketId(),
                mapping.shardName(),
                mapping.connectionKey(),
                mapping.state(),
                mapping.shardStatus(),
                mapping.version()
        );
    }
}