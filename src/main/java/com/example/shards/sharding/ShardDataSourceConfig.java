package com.example.shards.sharding;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 샤드 A와 B에 사용할 DB 연결 풀을 등록합니다.
@Configuration
public class ShardDataSourceConfig {

    // shardA라는 구분 표시를 붙입니다.
    @Qualifier("shardA")
    // 기존 메타 DB 자동 설정을 유지하며 추가 연결로 등록합니다.
    // 앱 종료 시 close()를 호출해 연결 풀을 정리합니다.
    @Bean(defaultCandidate = false, destroyMethod = "close")
    // application.properties의 이 묶음을 읽어 설정합니다.
    @ConfigurationProperties("app.datasource.shard-a")
    public HikariDataSource shardADataSource() {
        return new HikariDataSource();
    }

    // shardB라는 구분 표시를 붙입니다.
    @Qualifier("shardB")
    @Bean(defaultCandidate = false, destroyMethod = "close")
    @ConfigurationProperties("app.datasource.shard-b")
    public HikariDataSource shardBDataSource() {
        return new HikariDataSource();
    }
}