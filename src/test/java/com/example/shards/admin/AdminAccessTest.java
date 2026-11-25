package com.example.shards.admin;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAccessTest {

    private final AdminAccess access = new AdminAccess("reader-test-only", "writer-test-only");

    @Test
    void permitsReadToBothRolesAndWriteToAdministrator() {
        access.requireRead("Bearer reader-test-only");
        access.requireRead("Bearer writer-test-only");
        access.requireWrite("Bearer writer-test-only");
    }

    @Test
    void deniesWriteToReader() {
        assertThatThrownBy(() -> access.requireWrite("Bearer reader-test-only"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void deniesMissingOrUnconfiguredTokens() {
        assertThatThrownBy(() -> access.requireRead(null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(401));
        assertThatThrownBy(() -> new AdminAccess("", "").requireWrite("Bearer "))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(401));
    }
}
