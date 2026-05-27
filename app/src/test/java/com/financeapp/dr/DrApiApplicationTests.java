package com.financeapp.dr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeapp.dr.assistant.openai.OpenAiClient;
import com.financeapp.dr.assistant.openai.OpenAiCompletionResult;
import com.financeapp.dr.assistant.openai.OpenAiMessage;
import com.financeapp.dr.assistant.openai.OpenAiToolCall;
import com.financeapp.dr.model.DepositRequest;
import com.financeapp.dr.model.PaymentMethodType;
import com.financeapp.dr.model.TransferRequest;
import com.financeapp.dr.model.WithdrawRequest;
import com.financeapp.dr.security.JwtAuthFilter;
import com.financeapp.dr.service.LedgerService;
import com.financeapp.dr.support.CsrfTestSupport;
import com.financeapp.dr.support.CsrfTestSupport.CsrfTokens;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;

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

    @MockBean
    private OpenAiClient openAiClient;

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
        CsrfTokens csrf = CsrfTestSupport.fetch(mockMvc, "/signup");

        MvcResult result = mockMvc.perform(post("/signup")
                        .cookie(csrf.cookie())
                        .param(CsrfTestSupport.CSRF_PARAM, csrf.token())
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
        CsrfTokens csrf = CsrfTestSupport.fetch(mockMvc, "/signup");
        MvcResult res = mockMvc.perform(post("/signup")
                        .cookie(csrf.cookie())
                        .param(CsrfTestSupport.CSRF_PARAM, csrf.token())
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
    void browserAuditPageRendersSecurityLog() throws Exception {
        Cookie jwt = signup("auditpage" + randomSuffix(), "S3cure-Banking!Pass");
        mockMvc.perform(get("/audit").cookie(jwt))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Security &amp; activity log")))
                .andExpect(content().string(anyOf(
                        containsString("SIGNUP"),
                        containsString("LOGIN_SUCCESS"),
                        containsString("No security events recorded yet"))));
    }

    @Test
    void assistantChatWithoutAuthReturns401() throws Exception {
        mockMvc.perform(post("/api/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"What's my balance?\"}]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assistantChatWhenNotConfiguredReturns503() throws Exception {
        Cookie cookie = signup("assist" + randomSuffix(), "S3cure-Banking!Pass");
        when(openAiClient.isConfigured()).thenReturn(false);
        when(openAiClient.unavailableMessage()).thenReturn("Assistant is unavailable. Start Ollama locally: ollama serve && ollama pull llama3.1");

        mockMvc.perform(post("/api/assistant/chat")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"What's my balance?\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Ollama")));
    }

    @Test
    void assistantChatReturnsBalanceViaFastPathWithoutLlm() throws Exception {
        Cookie cookie = signup("assistbal" + randomSuffix(), "S3cure-Banking!Pass");
        when(openAiClient.isConfigured()).thenReturn(true);

        mockMvc.perform(post("/api/assistant/chat")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"What's my balance?\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("$1,000.00")));

        verify(openAiClient, never()).complete(any(), any());
    }

    @Test
    void assistantChatReturnsNavigationLinkViaFastPath() throws Exception {
        Cookie cookie = signup("assistnav" + randomSuffix(), "S3cure-Banking!Pass");
        when(openAiClient.isConfigured()).thenReturn(true);

        mockMvc.perform(post("/api/assistant/chat")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"Take me to withdraw\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links[0].href").value("/withdraw"))
                .andExpect(jsonPath("$.links[0].label").value("Withdraw"));

        verify(openAiClient, never()).complete(any(), any());
    }

    @Test
    void assistantChatReturnsSpendingHistoryViaFastPathWithoutLlm() throws Exception {
        Cookie cookie = signup("assisttxn" + randomSuffix(), "S3cure-Banking!Pass");
        when(openAiClient.isConfigured()).thenReturn(true);

        mockMvc.perform(post("/api/assistant/chat")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"Show spending history\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").exists());

        verify(openAiClient, never()).complete(any(), any());
    }

    @Test
    void assistantConfirmExecutesProposedTransfer() throws Exception {
        String sender = "asistsnd" + randomSuffix();
        String receiver = "asistrcv" + randomSuffix();
        Cookie senderCookie = signup(sender, "S3cure-Banking!Pass");
        Cookie receiverCookie = signup(receiver, "S3cure-Banking!Pass");

        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.complete(any(), any()))
                .thenReturn(toolCallResult("propose_transfer",
                        "{\"toUsername\":\"" + receiver + "\",\"amount\":25,\"memo\":\"assistant test\"}"));

        MvcResult chatResult = mockMvc.perform(post("/api/assistant/chat")
                        .cookie(senderCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"Send $25 to " + receiver + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingAction.summary").exists())
                .andReturn();

        String pendingActionId = objectMapper.readTree(chatResult.getResponse().getContentAsString())
                .get("pendingAction").get("id").asText();

        mockMvc.perform(post("/api/assistant/confirm")
                        .cookie(senderCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingActionId\":\"" + pendingActionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("completed")));

        String senderAccountId = ledger.findUserByUsername(sender).orElseThrow().user().defaultAccountId();
        String receiverAccountId = ledger.findUserByUsername(receiver).orElseThrow().user().defaultAccountId();
        assertThat(balanceOf(senderAccountId, senderCookie)).isEqualByComparingTo(new BigDecimal("975.00"));
        assertThat(balanceOf(receiverAccountId, receiverCookie)).isEqualByComparingTo(new BigDecimal("1025.00"));
    }

    @Test
    void loginWithoutCsrfRedirectsToStaleHint() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "alice")
                        .param("password", "Password!1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?stale=1"));
    }

    @Test
    void loginWithCsrfIssuesJwtCookieAndRedirectsToDashboard() throws Exception {
        String username = "formlogin" + randomSuffix();
        String password = "S3cure-Banking!Pass";
        signup(username, password);

        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());

        CsrfTokens csrf = CsrfTestSupport.fetch(mockMvc, "/login");
        mockMvc.perform(post("/login")
                        .cookie(csrf.cookie())
                        .param(CsrfTestSupport.CSRF_PARAM, csrf.token())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(cookie().exists(JwtAuthFilter.COOKIE_NAME));
    }

    @Test
    void directErrorEndpointRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/error"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void poisonedLoginReturnUrlIsReset() throws Exception {
        mockMvc.perform(get("/login").param("returnUrl", "/error?returnUrl=/error"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginWithErrorReturnUrlRedirectsToDashboard() throws Exception {
        String username = "errurl" + randomSuffix();
        String password = "S3cure-Banking!Pass";
        signup(username, password);

        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());

        CsrfTokens csrf = CsrfTestSupport.fetch(mockMvc, "/login?returnUrl=/error");
        mockMvc.perform(post("/login")
                        .cookie(csrf.cookie())
                        .param(CsrfTestSupport.CSRF_PARAM, csrf.token())
                        .param("username", username)
                        .param("password", password)
                        .param("returnUrl", "/error"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(cookie().exists(JwtAuthFilter.COOKIE_NAME));
    }

    @Test
    void missingFaviconDoesNotRedirectToLogin() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isOk());
    }

    @Test
    void browserTransferWithoutCsrfRedirectsToStaleForm() throws Exception {
        Cookie jwt = signup("paycsrf" + randomSuffix(), "S3cure-Banking!Pass");
        mockMvc.perform(post("/transfer")
                        .cookie(jwt)
                        .header("Referer", "http://localhost/transfer")
                        .param("toUsername", "bob")
                        .param("amount", "10.00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/transfer?stale=1"));
    }

    @Test
    void browserTransferAcceptsAtPrefixedRecipient() throws Exception {
        String password = "S3cure-Banking!Pass";
        String receiver = "recv" + randomSuffix();
        Cookie senderJwt = signup("send" + randomSuffix(), password);
        signup(receiver, password);

        CsrfTokens csrf = CsrfTestSupport.fetch(mockMvc, "/transfer", senderJwt);
        mockMvc.perform(post("/transfer")
                        .cookie(senderJwt, csrf.cookie())
                        .param(CsrfTestSupport.CSRF_PARAM, csrf.token())
                        .param("toUsername", "@" + receiver)
                        .param("amount", "25.00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard?transferred=1"));
    }

    @Test
    void browserDepositPostsSuccessfully() throws Exception {
        Cookie jwt = signup("deposits" + randomSuffix(), "S3cure-Banking!Pass");
        CsrfTokens csrf = CsrfTestSupport.fetch(mockMvc, "/deposit", jwt);
        mockMvc.perform(post("/deposit")
                        .cookie(jwt, csrf.cookie())
                        .param(CsrfTestSupport.CSRF_PARAM, csrf.token())
                        .param("amount", "50.00")
                        .param("description", "Browser deposit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard?deposited=1"));
    }

    @Test
    void browserWithdrawPostsSuccessfully() throws Exception {
        Cookie jwt = signup("withdraws" + randomSuffix(), "S3cure-Banking!Pass");
        CsrfTokens csrf = CsrfTestSupport.fetch(mockMvc, "/withdraw", jwt);
        mockMvc.perform(post("/withdraw")
                        .cookie(jwt, csrf.cookie())
                        .param(CsrfTestSupport.CSRF_PARAM, csrf.token())
                        .param("amount", "10.00")
                        .param("paypalEmail", "alice@paypal.demo"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard?withdrawn=1"));
    }

    @Test
    void browserLogoutClearsSessionAndRedirectsToSignedOutLogin() throws Exception {
        String username = "logout" + randomSuffix();
        String password = "S3cure-Banking!Pass";
        Cookie jwt = signup(username, password);

        CsrfTokens csrf = CsrfTestSupport.fetch(mockMvc, "/dashboard");
        MvcResult logoutResult = mockMvc.perform(post("/logout")
                        .cookie(jwt, csrf.cookie())
                        .param(CsrfTestSupport.CSRF_PARAM, csrf.token()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?signedOut=1"))
                .andExpect(cookie().maxAge(JwtAuthFilter.COOKIE_NAME, 0))
                .andReturn();

        Cookie clearedJwt = logoutResult.getResponse().getCookie(JwtAuthFilter.COOKIE_NAME);
        assertThat(clearedJwt.getMaxAge()).isZero();

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void apiAuthLoginAndLogout() throws Exception {
        String username = "apiauth" + randomSuffix();
        signup(username, "S3cure-Banking!Pass");

        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"S3cure-Banking!Pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(cookie().exists(JwtAuthFilter.COOKIE_NAME));

        mockMvc.perform(get("/api/auth/me").cookie(
                        mockMvc.perform(post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"username\":\"" + username + "\",\"password\":\"S3cure-Banking!Pass\"}"))
                                .andReturn().getResponse().getCookie(JwtAuthFilter.COOKIE_NAME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void authenticatedUserRedirectedFromLoginPage() throws Exception {
        Cookie cookie = signup("loginredir" + randomSuffix(), "S3cure-Banking!Pass");
        mockMvc.perform(get("/login").cookie(cookie))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));
    }

    @Test
    void expiredJwtRedirectsToLoginWithExpiredFlag() throws Exception {
        String username = "expired" + randomSuffix();
        Cookie cookie = signup(username, "S3cure-Banking!Pass");
        var user = ledger.findUserByUsername(username).orElseThrow().user();

        String expiredToken = Jwts.builder()
                .subject(user.userId())
                .claims(Map.of(
                        "username", user.username(),
                        "displayName", user.displayName(),
                        "accountId", user.defaultAccountId()))
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(
                        "test-only-jwt-secret-32-bytes-long-1234".getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/dashboard").cookie(new Cookie(JwtAuthFilter.COOKIE_NAME, expiredToken)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired=1"));
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
        CsrfTokens csrf = CsrfTestSupport.fetch(mockMvc, "/signup");
        MvcResult res = mockMvc.perform(post("/signup")
                        .cookie(csrf.cookie())
                        .param(CsrfTestSupport.CSRF_PARAM, csrf.token())
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

    private static OpenAiCompletionResult toolCallResult(String toolName, String args) {
        OpenAiToolCall toolCall = new OpenAiToolCall(
                "call_1",
                "function",
                new OpenAiToolCall.OpenAiFunctionCall(toolName, args)
        );
        OpenAiMessage message = OpenAiMessage.assistant(null, List.of(toolCall));
        return new OpenAiCompletionResult(new OpenAiCompletionResult.OpenAiChoice(message));
    }

    private static OpenAiCompletionResult textResult(String text) {
        OpenAiMessage message = new OpenAiMessage("assistant", text, null, null);
        return new OpenAiCompletionResult(new OpenAiCompletionResult.OpenAiChoice(message));
    }
}
