package com.financeapp.dr.web;

import com.financeapp.dr.config.AppProperties;
import com.financeapp.dr.model.SignupRequest;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.model.UserWithPasswordHash;
import com.financeapp.dr.security.AuthenticatedUser;
import com.financeapp.dr.security.JwtAuthFilter;
import com.financeapp.dr.security.JwtService;
import com.financeapp.dr.security.StrongPasswordValidator;
import com.financeapp.dr.service.LedgerService;
import com.financeapp.dr.service.UsernameAlreadyExistsException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final long LOGIN_FAILURE_DELAY_MS = 250L;

    private final LedgerService ledger;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties properties;

    public AuthController(LedgerService ledger,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          AppProperties properties) {
        this.ledger = ledger;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @ModelAttribute("brand")
    public String brand() {
        return "iTrust";
    }

    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "error", required = false) String error,
                            @RequestParam(value = "signedUp", required = false) String signedUp,
                            Model model) {
        if (error != null) {
            model.addAttribute("error", "Invalid username or password.");
        }
        if (signedUp != null) {
            model.addAttribute("notice", "Account created. Please sign in.");
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam("username") String username,
                        @RequestParam("password") String password,
                        HttpServletRequest request,
                        HttpServletResponse response) {
        String ip = ClientInfo.ip(request);
        String ua = ClientInfo.userAgent(request);
        Optional<UserWithPasswordHash> userOpt = ledger.findUserByUsername(username);
        boolean ok = userOpt.isPresent() && passwordEncoder.matches(password, userOpt.get().passwordHash());
        if (!ok) {
            sleep(LOGIN_FAILURE_DELAY_MS + ThreadLocalRandom.current().nextLong(0, 75));
            ledger.recordLoginFailure(username, ip, ua, userOpt.isPresent() ? "bad_password" : "unknown_user");
            return "redirect:/login?error=1";
        }
        UserResponse user = userOpt.get().user();
        String token = jwtService.issue(new AuthenticatedUser(
                user.userId(), user.username(), user.displayName(), user.defaultAccountId()));
        response.addCookie(buildAuthCookie(token, (int) jwtService.validity().getSeconds()));
        ledger.recordLoginSuccess(user.userId(), user.username(), ip, ua);
        return "redirect:/dashboard";
    }

    @GetMapping("/signup")
    public String signupPage(Model model) {
        if (!model.containsAttribute("signup")) {
            model.addAttribute("signup", new SignupRequest("", "", "", ""));
        }
        model.addAttribute("passwordRules", new String[]{
                "At least " + StrongPasswordValidator.MIN_LENGTH + " characters",
                "An uppercase letter (A-Z)",
                "A lowercase letter (a-z)",
                "A digit (0-9)",
                "A symbol (!@#$%^&*...)",
                "Not in the list of commonly leaked passwords"
        });
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@RequestParam("username") String username,
                         @RequestParam("displayName") String displayName,
                         @RequestParam("password") String password,
                         @RequestParam("confirmPassword") String confirmPassword,
                         HttpServletRequest request,
                         HttpServletResponse response,
                         Model model) {
        SignupRequest req = new SignupRequest(
                username == null ? "" : username.trim().toLowerCase(),
                displayName == null ? "" : displayName.trim(),
                password == null ? "" : password,
                confirmPassword == null ? "" : confirmPassword);

        String fieldError = validate(req);
        if (fieldError != null) {
            model.addAttribute("error", fieldError);
            model.addAttribute("signup", new SignupRequest(req.username(), req.displayName(), "", ""));
            return signupPage(model);
        }

        try {
            UserResponse user = ledger.signup(req, ClientInfo.ip(request), ClientInfo.userAgent(request));
            String token = jwtService.issue(new AuthenticatedUser(
                    user.userId(), user.username(), user.displayName(), user.defaultAccountId()));
            response.addCookie(buildAuthCookie(token, (int) jwtService.validity().getSeconds()));
            ledger.recordLoginSuccess(user.userId(), user.username(),
                    ClientInfo.ip(request), ClientInfo.userAgent(request));
            return "redirect:/dashboard?welcome=1";
        } catch (UsernameAlreadyExistsException ex) {
            model.addAttribute("error", "That username is already taken. Please pick another.");
            model.addAttribute("signup", new SignupRequest(req.username(), req.displayName(), "", ""));
            return signupPage(model);
        } catch (RuntimeException ex) {
            log.warn("Signup failed for {}: {}", req.username(), ex.getMessage());
            model.addAttribute("error", "Could not create your account. " + ex.getMessage());
            model.addAttribute("signup", new SignupRequest(req.username(), req.displayName(), "", ""));
            return signupPage(model);
        }
    }

    private String validate(SignupRequest req) {
        if (req.username().length() < 3 || req.username().length() > 30) {
            return "Username must be 3-30 characters.";
        }
        if (!req.username().matches("^[a-z0-9._-]+$")) {
            return "Username may contain only lowercase letters, digits, dots, underscores, and dashes.";
        }
        if (req.displayName().isBlank() || req.displayName().length() > 80) {
            return "Display name is required (max 80 characters).";
        }
        String pwErr = StrongPasswordValidator.firstFailure(req.password());
        if (pwErr != null) {
            return pwErr;
        }
        if (!req.passwordsMatch()) {
            return "Passwords do not match.";
        }
        return null;
    }

    private Cookie buildAuthCookie(String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(JwtAuthFilter.COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.cookie().secure());
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
