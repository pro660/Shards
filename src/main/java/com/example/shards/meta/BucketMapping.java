package com.example.shards.meta;

// 메타 DB에서 조회한 버킷과 담당 샤드 정보를 함께 담습니다.
public record BucketMapping(
        // 조회한 버킷 번호입니다.
        int bucketId,

        // 현재 담당 샤드의 숫자 식별자입니다.
        int shardId,

        // 사람이 알아보기 쉬운 샤드 이름입니다.
        String shardName,

        // 나중에 서버의 DB 연결 설정을 찾을 때 사용할 키입니다.
        String connectionKey,

        // 버킷의 상태입니다. NORMAL 또는 MIGRATING입니다.
        String state,

        // 매핑 변경 여부를 구분하는 버전 번호입니다.
        long version,

        // 담당 샤드의 사용 설정입니다. ACTIVE 또는 INACTIVE입니다.
        String shardStatus
) {
}