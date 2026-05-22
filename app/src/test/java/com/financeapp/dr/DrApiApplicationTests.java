package com.financeapp.dr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeapp.dr.model.DepositRequest;
import com.financeapp.dr.model.PaymentMethodType;
import com.financeapp.dr.model.TransferRequest;
import com.financeapp.dr.model.WithdrawRequest;
import com.financeapp.dr.security.JwtAuthFilter;
import com.financeapp.dr.service.LedgerService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-jwt-secret-32-bytes-long-1234",
        "app.seed.enabled=false"
})
class DrApiApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerService ledger;

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void dashboardWithoutAuthRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void signupCreatesAccountWithWelcomeBonusDemoCardAndLogsIn() throws Exception {
        String username = "newuser" + randomSuffix();
        String password = "S3cure-Banking!Pass";

        MvcResult result = mockMvc.perform(post("/signup")
                        .param("username", username)
                        .param("displayName", "New User")
                        .param("password", password)
                        .param("confirmPassword", password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard?welcome=1"))
                .andExpect(cookie().exists(JwtAuthFilter.COOKIE_NAME))
                .andReturn();

        Cookie jwtCookie = result.getResponse().getCookie(JwtAuthFilter.COOKIE_NAME);
        assertThat(jwtCookie).isNotNull();

        MvcResult meResult = mockMvc.perform(get("/api/me").cookie(jwtCookie))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode me = objectMapper.readTree(meResult.getResponse().getContentAsString());
        assertThat(me.get("username").asText()).isEqualTo(username);
        String userId = me.get("userId").asText();
        String accountId = me.get("accountId").asText();

        assertThat(ledger.getDemoCard(userId)).isPresent();
        assertThat(ledger.getDemoCard(userId).orElseThrow().type()).isEqualTo(PaymentMethodType.DEMO_CARD);

        MvcResult accountResult = mockMvc.perform(get("/api/accounts/" + accountId).cookie(jwtCookie))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode account = objectMapper.readTree(accountResult.getResponse().getContentAsString());
        assertThat(new BigDecimal(account.get("balance").asText())).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    void weakPasswordSignupShowsError() throws Exception {
        MvcResult res = mockMvc.perform(post("/signup")
                        .param("username", "weak" + randomSuffix())
                        .param("displayName", "Weak")
                        .param("password", "short1!")
                        .param("confirmPassword", "short1!"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).contains("at least 12");
    }

    @Test
    void transferMovesFundsBetweenUsersAtomically() throws Exception {
        String fromUsername = "sender" + randomSuffix();
        String toUsername = "receiver" + randomSuffix();
        String password = "S3cure-Banking!Pass";

        Cookie senderCookie = signup(fromUsername, password);
        Cookie receiverCookie = signup(toUsername, password);

        String senderAccountId = ledger.findUserByUsername(fromUsername).orElseThrow().user().defaultAccountId();
        String receiverAccountId = ledger.findUserByUsername(toUsername).orElseThrow().user().defaultAccountId();

        TransferRequest req = new TransferRequest(toUsername, new BigDecimal("250.00"), "rent split");
        mockMvc.perform(post("/api/transfers")
                        .cookie(senderCookie)
                        .header("X-Request-Id", "req-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderTransaction.type").value("TRANSFER_OUT"))
                .andExpect(jsonPath("$.senderTransaction.paymentMethod").value("P2P"))
                .andExpect(jsonPath("$.receiverTransaction.type").value("TRANSFER_IN"));

        assertThat(balanceOf(senderAccountId, senderCookie)).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(balanceOf(receiverAccountId, receiverCookie)).isEqualByComparingTo(new BigDecimal("1250.00"));
    }

    @Test
    void transferOverBalanceReturnsUnprocessable() throws Exception {
        String username = "broke" + randomSuffix();
        String other = "rich" + randomSuffix();
        Cookie cookie = signup(username, "S3cure-Banking!Pass");
        signup(other, "S3cure-Banking!Pass");

        TransferRequest req = new TransferRequest(other, new BigDecimal("9999.99"), "too much");
        mockMvc.perform(post("/api/transfers")
                        .cookie(cookie)
                        .header("X-Request-Id", "req-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity());

        String accountId = ledger.findUserByUsername(username).orElseThrow().user().defaultAccountId();
        assertThat(balanceOf(accountId, cookie)).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    void auditLogExposesSignupLoginTransferAndPaymentEvents() throws Exception {
        String username = "audited" + randomSuffix();
        String other = "peer" + randomSuffix();
        Cookie cookie = signup(username, "S3cure-Banking!Pass");
        signup(other, "S3cure-Banking!Pass");
        String accountId = ledger.findUserByUsername(username).orElseThrow().user().defaultAccountId();

        mockMvc.perform(post("/api/transfers")
                        .cookie(cookie)
                        .header("X-Request-Id", "req-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(other, new BigDecimal("10.00"), "audit test"))))
                .andExpect(status().isCreated());

        DepositRequest depositReq = new DepositRequest(new BigDecimal("25.00"), "card top-up", PaymentMethodType.DEMO_CARD);
        mockMvc.perform(post("/api/accounts/" + accountId + "/deposit")
                        .cookie(cookie)
                        .header("X-Request-Id", "req-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(depositReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentMethod").value("DEMO_CARD"));

        WithdrawRequest withdrawReq = new WithdrawRequest(new BigDecimal("15.00"), "paypal cash out", "user@paypal.demo");
        mockMvc.perform(post("/api/accounts/" + accountId + "/withdraw")
                        .cookie(cookie)
                        .header("X-Request-Id", "req-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withdrawReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentMethod").value("DEMO_PAYPAL"))
                .andExpect(jsonPath("$.paymentReference").value("user@paypal.demo"));

        MvcResult audit = mockMvc.perform(get("/api/audit/me").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn();
        String events = objectMapper.readTree(audit.getResponse().getContentAsString()).get("items").toString();
        assertThat(events).contains("SIGNUP");
        assertThat(events).contains("LOGIN_SUCCESS");
        assertThat(events).contains("TRANSFER_OUT");
        assertThat(events).contains("DEPOSIT_CARD");
        assertThat(events).contains("WITHDRAWAL_PAYPAL");
    }

    @Test
    void apiCallWithoutAuthReturns401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cardDepositIncreasesBalanceWithPaymentFields() throws Exception {
        String username = "depositor" + randomSuffix();
        Cookie cookie = signup(username, "S3cure-Banking!Pass");
        String accountId = ledger.findUserByUsername(username).orElseThrow().user().defaultAccountId();

        DepositRequest req = new DepositRequest(new BigDecimal("123.45"), "payday", PaymentMethodType.DEMO_CARD);
        mockMvc.perform(post("/api/accounts/" + accountId + "/deposit")
                        .cookie(cookie)
                        .header("X-Request-Id", "req-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.paymentMethod").value("DEMO_CARD"))
                .andExpect(jsonPath("$.balanceAfter").value(1123.45));

        assertThat(balanceOf(accountId, cookie)).isEqualByComparingTo(new BigDecimal("1123.45"));
    }

    @Test
    void paypalWithdrawDecreasesBalance() throws Exception {
        String username = "withdrawer" + randomSuffix();
        Cookie cookie = signup(username, "S3cure-Banking!Pass");
        String accountId = ledger.findUserByUsername(username).orElseThrow().user().defaultAccountId();

        WithdrawRequest req = new WithdrawRequest(new BigDecimal("100.00"), "paypal", "alice@paypal.demo");
        mockMvc.perform(post("/api/accounts/" + accountId + "/withdraw")
                        .cookie(cookie)
                        .header("X-Request-Id", "req-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.paymentReference").value("alice@paypal.demo"));

        assertThat(balanceOf(accountId, cookie)).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(ledger.getDemoPayPal(ledger.findUserByUsername(username).orElseThrow().user().userId()))
                .isPresent();
    }

    @Test
    void withdrawOverBalanceReturnsUnprocessable() throws Exception {
        String username = "nowith" + randomSuffix();
        Cookie cookie = signup(username, "S3cure-Banking!Pass");
        String accountId = ledger.findUserByUsername(username).orElseThrow().user().defaultAccountId();

        WithdrawRequest req = new WithdrawRequest(new BigDecimal("5000.00"), "too much", "broke@paypal.demo");
        mockMvc.perform(post("/api/accounts/" + accountId + "/withdraw")
                        .cookie(cookie)
                        .header("X-Request-Id", "req-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(balanceOf(accountId, cookie)).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    private Cookie signup(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/signup")
                        .param("username", username)
                        .param("displayName", username)
                        .param("password", password)
                        .param("confirmPassword", password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/dashboard*"))
                .andReturn();
        return res.getResponse().getCookie(JwtAuthFilter.COOKIE_NAME);
    }

    private BigDecimal balanceOf(String accountId, Cookie cookie) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/accounts/" + accountId).cookie(cookie))
                .andExpect(status().isOk())
                .andReturn();
        return new BigDecimal(objectMapper.readTree(res.getResponse().getContentAsString()).get("balance").asText());
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 6).toLowerCase();
    }
}
