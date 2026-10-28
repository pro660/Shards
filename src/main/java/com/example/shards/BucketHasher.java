package com.example.shards.sharding;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// 사용자 ID로 버킷 번호를 계산하는 클래스입니다.
public final class BucketHasher {

    // 샤드 개수와 관계없이 버킷은 16개로 고정합니다.
    private static final int BUCKET_COUNT = 16;

    // 계산 기능만 사용하므로 객체를 직접 만들지 못하게 합니다.
    private BucketHasher() {
    }

    // 사용자 ID를 받아 0~15 중 하나의 버킷 번호를 반환합니다.
    public static int toBucketId(long userId) {

        // DB의 user_id 조건과 동일하게 양의 정수만 허용합니다.
        if (userId <= 0) {
            throw new IllegalArgumentException(
                    "userId는 1 이상이어야 합니다."
            );
        }

        // 예: 숫자 101을 문자열 "101"로 바꿉니다.
        String userIdText = Long.toString(userId);

        // 어느 컴퓨터에서 실행해도 같은 바이트가 되도록 UTF-8을 지정합니다.
        byte[] inputBytes = userIdText.getBytes(StandardCharsets.UTF_8);

        try {
            // 호출할 때마다 계산 도구를 새로 만들어 사용합니다.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // 입력값의 SHA-256 해시를 계산합니다.
            byte[] hashBytes = digest.digest(inputBytes);

            // 첫 4바이트를 큰 자리부터 읽어 int 값으로 변환합니다.
            // ByteBuffer의 기본 읽기 순서는 BIG_ENDIAN입니다.
            int hashValue = ByteBuffer.wrap(hashBytes).getInt();

            // hashValue가 음수여도 버킷 번호는 0~15가 되게 합니다.
            return Math.floorMod(hashValue, BUCKET_COUNT);

        } catch (NoSuchAlgorithmException e) {
            // 해시 계산을 못 하면 잘못된 버킷으로 보내지 않고 실패시킵니다.
            throw new IllegalStateException(
                    "SHA-256 해시 알고리즘을 사용할 수 없습니다.",
                    e
            );
        }
    }
}