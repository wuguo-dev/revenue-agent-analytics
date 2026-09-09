package com.salesanalytics.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    @Test
    void issuesAndParsesCurrentUser() {
        JwtService service = new JwtService("01234567890123456789012345678901", Duration.ofHours(2));
        CurrentUser original = new CurrentUser(7L, "admin", "管理员", "ADMIN", null);

        CurrentUser parsed = service.parse(service.issue(original));

        assertThat(parsed).isEqualTo(original);
    }
}
