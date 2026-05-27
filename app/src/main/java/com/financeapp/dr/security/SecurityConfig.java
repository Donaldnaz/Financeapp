package com.financeapp.dr.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.StringUtils;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   OAuth2LoginSuccessHandler oauth2LoginSuccessHandler,
                                                   @Value("${GOOGLE_CLIENT_ID:}")
                                                   String googleClientId) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookiePath("/");

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers(new AntPathRequestMatcher("/api/**"))
                )
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            new AntPathRequestMatcher("/"),
                            new AntPathRequestMatcher("/login"),
                            new AntPathRequestMatcher("/logout"),
                            new AntPathRequestMatcher("/signup"),
                            new AntPathRequestMatcher("/api/signup"),
                            new AntPathRequestMatcher("/api/auth/login"),
                            new AntPathRequestMatcher("/api/auth/logout"),
                            new AntPathRequestMatcher("/health"),
                            new AntPathRequestMatcher("/actuator/health/**"),
                            new AntPathRequestMatcher("/css/**"),
                            new AntPathRequestMatcher("/js/**"),
                            new AntPathRequestMatcher("/webjars/**"),
                            new AntPathRequestMatcher("/favicon.ico"),
                            new AntPathRequestMatcher("/error")
                    ).permitAll();
                    if (StringUtils.hasText(googleClientId)) {
                        auth.requestMatchers(
                                new AntPathRequestMatcher("/oauth2/**"),
                                new AntPathRequestMatcher("/login/oauth2/**")
                        ).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(eh -> eh
                        .accessDeniedHandler(new CsrfAccessDeniedHandler())
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/**")
                        )
                        .defaultAuthenticationEntryPointFor(
                                new RedirectToLoginEntryPoint(),
                                new AntPathRequestMatcher("/**")
                        )
                )
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .deleteCookies(JwtAuthFilter.COOKIE_NAME)
                        .invalidateHttpSession(true)
                        .logoutSuccessUrl("/login?signedOut=1")
                );

        if (StringUtils.hasText(googleClientId)) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login")
                    .successHandler(oauth2LoginSuccessHandler));
        }

        return http.build();
    }
}
