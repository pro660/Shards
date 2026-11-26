package com.example.shards.meta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 모든 라우팅에 필요한 메타 DB를 확인합니다. 개별 샤드 연결은 요청 시 확인합니다.
@Component
public class MetaDbConnCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MetaDbConnCheck.class);
    private final JdbcTemplate jdbcTemplate;

    public MetaDbConnCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {

        String databaseName = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        log.info("Connected to metadata database {}", databaseName);
    }
}
