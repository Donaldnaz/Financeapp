package com.financeapp.dr.bootstrap;

import com.financeapp.dr.config.AppProperties;
import com.financeapp.dr.model.AccountRequest;
import com.financeapp.dr.model.AccountResponse;
import com.financeapp.dr.model.PaymentMethodType;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class UserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);
    private static final BigDecimal STARTING_BONUS = new BigDecimal("1000.00");

    private static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser("alice", "Password!1", "Alice Anderson"),
            new DemoUser("bob", "Password!1", "Bob Brown"),
            new DemoUser("carol", "Password!1", "Carol Chen"),
            new DemoUser("dave", "Password!1", "Dave Davies"),
            new DemoUser("eve", "Password!1", "Eve Evans")
    );

    private final LedgerService ledger;
    private final AppProperties properties;

    public UserSeeder(LedgerService ledger, AppProperties properties) {
        this.ledger = ledger;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        log.info("Seeding {} demo users (region={}, store={})",
                DEMO_USERS.size(), properties.region(), properties.storage().type());

        for (DemoUser demo : DEMO_USERS) {
            try {
                UserResponse user = ledger.findUserByUsername(demo.username)
                        .map(u -> u.user())
                        .orElseGet(() -> {
                            AccountResponse account = ledger.createAccount(
                                    new AccountRequest(demo.displayName + " Checking", "USD"));
                            UserResponse created = ledger.createUser(demo.username, demo.password,
                                    demo.displayName, account.accountId());
                            ledger.ensureDemoCard(created.userId(), created.username());
                            log.info("Seeded demo user '{}' (userId={}, accountId={})",
                                    created.username(), created.userId(), created.defaultAccountId());
                            return created;
                        });

                ledger.ensureDemoCard(user.userId(), user.username());
                applyStartingBonus(user);
            } catch (Exception ex) {
                log.warn("Failed to seed user '{}': {}", demo.username, ex.getMessage());
            }
        }

        log.info("Seed complete. Log in at /login with username/password from app/README.md");
    }

    private void applyStartingBonus(UserResponse user) {
        String requestId = "seed-bonus-" + user.username();
        try {
            ledger.deposit(requestId, user.defaultAccountId(), user.userId(), user.username(),
                    STARTING_BONUS, "Welcome bonus", PaymentMethodType.WELCOME_BONUS,
                    "seed", "system");
            log.info("Applied $1000 starting bonus to {} (idempotent on requestId={})",
                    user.username(), requestId);
        } catch (Exception ex) {
            log.warn("Could not apply starting bonus to {}: {}", user.username(), ex.getMessage());
        }
    }

    private record DemoUser(String username, String password, String displayName) {
    }
}
