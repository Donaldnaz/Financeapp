package com.financeapp.dr.assistant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantIntentRouterTest {

    private AssistantIntentRouter router;

    @BeforeEach
    void setUp() {
        router = new AssistantIntentRouter();
    }

    @Test
    void routesBalanceQuestions() {
        assertThat(router.route("What's my balance?").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.BALANCE);
        assertThat(router.route("How much money do I have?").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.BALANCE);
    }

    @Test
    void routesTransactionsAndLimit() {
        AssistantIntentRouter.RoutedIntent routed = router.route("Show my last 3 transactions");
        assertThat(routed.intent()).isEqualTo(AssistantIntentRouter.AssistantIntent.TRANSACTIONS);
        assertThat(routed.limit()).isEqualTo(3);
    }

    @Test
    void routesNavigationBeforeWriteIntent() {
        AssistantIntentRouter.RoutedIntent routed = router.route("Take me to transfer");
        assertThat(routed.intent()).isEqualTo(AssistantIntentRouter.AssistantIntent.NAVIGATION);
        assertThat(routed.navigationPage()).isEqualTo("transfer");
    }

    @Test
    void routesWriteIntents() {
        assertThat(router.route("Send $25 to bob").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.TRANSFER);
        assertThat(router.route("Deposit $100").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.DEPOSIT);
        assertThat(router.route("Withdraw $50 to PayPal").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.WITHDRAW);
    }

    @Test
    void routesPaymentMethodsAndAudit() {
        assertThat(router.route("What payment methods do I have?").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.PAYMENT_METHODS);
        assertThat(router.route("Show my audit log").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.AUDIT);
    }

    @Test
    void unknownWhenAmbiguous() {
        assertThat(router.route("Hello there").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.UNKNOWN);
    }

    @Test
    void routesPopularQueriesViaFastPath() {
        for (AssistantPopularQueries.PopularQuery query : AssistantPopularQueries.all()) {
            AssistantIntentRouter.RoutedIntent routed = router.route(query.message());
            assertThat(AssistantIntentRouter.isReadOnlyFastPath(routed.intent()))
                    .as("Expected fast path for: %s", query.message())
                    .isTrue();
        }

        assertThat(router.route("Take me to transfer").navigationPage()).isEqualTo("transfer");
        assertThat(router.route("Go to deposit").navigationPage()).isEqualTo("deposit");
    }

    @Test
    void routesSynonymPhrases() {
        assertThat(router.route("What's my checking account balance?").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.BALANCE);
        assertThat(router.route("Show spending history").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.TRANSACTIONS);
        assertThat(router.route("What cards are on file?").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.PAYMENT_METHODS);
        assertThat(router.route("Show recent logins").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.AUDIT);
        assertThat(router.route("transfer page").intent())
                .isEqualTo(AssistantIntentRouter.AssistantIntent.NAVIGATION);
        assertThat(router.route("transfer page").navigationPage()).isEqualTo("transfer");
    }
}
