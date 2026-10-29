package com.example.shards.meta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Spring이 이 클래스를 찾아서 객체를 만들고 관리하도록 합니다.
@Component
public class MetaDbConnCheck implements ApplicationRunner {

    // 연결 확인 결과를 애플리케이션 로그에 기록합니다.
    private static final Logger log =
            LoggerFactory.getLogger(MetaDbConnCheck.class);

    // SQL 실행을 도와주는 객체입니다.
    private final JdbcTemplate jdbcTemplate;

    // Spring이 준비한 JdbcTemplate을 생성자로 전달받습니다.
    public MetaDbConnCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 애플리케이션 시작 과정에서 한 번 실행됩니다.
    @Override
    public void run(ApplicationArguments args) {

        // 실제 DB에 SQL을 보내 현재 접속한 DB 이름을 가져옵니다.
        String databaseName = jdbcTemplate.queryForObject(
                "SELECT current_database()",
                String.class
        );

        // SQL 실행에 성공한 경우에만 이 로그가 출력됩니다.
        log.info("메타 DB 연결 확인 완료: {}", databaseName);
    }
}