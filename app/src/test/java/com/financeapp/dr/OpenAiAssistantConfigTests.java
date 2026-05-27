package com.financeapp.dr;

import com.financeapp.dr.security.JwtAuthFilter;
import com.financeapp.dr.support.CsrfTestSupport;
import com.financeapp.dr.support.CsrfTestSupport.CsrfTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-jwt-secret-32-bytes-long-1234",
        "app.seed.enabled=false",
        "app.assistant.provider=openai",
        "app.assistant.llm.api-key="
})
class OpenAiAssistantConfigTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void chatWithoutOpenAiKeyReturns503() throws Exception {
        Cookie cookie = signup("openai503" + System.nanoTime(), "S3cure-Banking!Pass");

        mockMvc.perform(post("/api/assistant/chat")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"What's my balance?\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(containsString("ASSISTANT_API_KEY")));
    }

    private Cookie signup(String username, String password) throws Exception {
        CsrfTokens csrf = CsrfTestSupport.fetch(mockMvc, "/signup");
        return mockMvc.perform(post("/signup")
                        .cookie(csrf.cookie())
                        .param(CsrfTestSupport.CSRF_PARAM, csrf.token())
                        .param("username", username)
                        .param("displayName", username)
                        .param("password", password)
                        .param("confirmPassword", password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/dashboard*"))
                .andReturn()
                .getResponse()
                .getCookie(JwtAuthFilter.COOKIE_NAME);
    }
}
