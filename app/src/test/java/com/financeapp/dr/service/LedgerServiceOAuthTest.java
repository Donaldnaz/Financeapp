package com.financeapp.dr.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-jwt-secret-32-bytes-long-1234",
        "app.seed.enabled=false"
})
class LedgerServiceOAuthTest {

    @Autowired
    private LedgerService ledger;

    @Test
    void signupOAuthCreatesAccountOnce() {
        String username = "google_test_" + System.nanoTime();
        var user = ledger.signupOAuth(username, "Google Tester", "127.0.0.1", "junit");
        assertThat(user.username()).isEqualTo(username);
        assertThat(user.displayName()).isEqualTo("Google Tester");

        var account = ledger.getAccount(user.defaultAccountId());
        assertThat(account.balance()).isEqualByComparingTo(new BigDecimal("1000.00"));

        var again = ledger.signupOAuth(username, "Google Tester", "127.0.0.1", "junit");
        assertThat(again.userId()).isEqualTo(user.userId());
    }
}
