package com.example.shards.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AdminAccess {

    private static final String BEARER_PREFIX = "Bearer ";
    private final byte[] readToken;
    private final byte[] writeToken;

    public AdminAccess(
            @Value("${APP_ADMIN_READ_TOKEN:}") String readToken,
            @Value("${APP_ADMIN_WRITE_TOKEN:}") String writeToken
    ) {
        this.readToken = readToken.getBytes(StandardCharsets.UTF_8);
        this.writeToken = writeToken.getBytes(StandardCharsets.UTF_8);
        if (this.readToken.length > 0 && MessageDigest.isEqual(this.readToken, this.writeToken)) {
            throw new IllegalArgumentException("조회용과 쓰기용 관리 토큰은 서로 달라야 합니다.");
        }
    }

    public void requireRead(String authorization) {
        require(authorization, false);
    }

    public void requireWrite(String authorization) {
        require(authorization, true);
    }

    private void require(String authorization, boolean write) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "관리 토큰이 필요합니다.");
        }
        byte[] supplied = authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        if (writeToken.length > 0 && MessageDigest.isEqual(writeToken, supplied)) {
            return;
        }
        if (readToken.length > 0 && MessageDigest.isEqual(readToken, supplied)) {
            if (write) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 쓰기 권한이 필요합니다.");
            }
            return;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효한 관리 토큰이 필요합니다.");
    }
}
