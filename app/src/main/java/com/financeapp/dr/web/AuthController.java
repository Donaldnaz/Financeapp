package com.financeapp.dr.web;

import com.financeapp.dr.model.SignupRequest;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.security.AuthenticatedUser;
import com.financeapp.dr.service.AuthService;
import com.financeapp.dr.service.LedgerService;
import com.financeapp.dr.service.UsernameAlreadyExistsException;
import com.financeapp.dr.security.ReturnUrlSupport;
import com.financeapp.dr.security.StrongPasswordValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final LedgerService ledger;
    private final AuthService authService;

    public AuthController(LedgerService ledger, AuthService authService) {
        this.ledger = ledger;
        this.authService = authService;
    }

    @ModelAttribute("brand")
    public String brand() {
        return "iTrust";
    }

    @GetMapping("/login")
    public String loginPage(@AuthenticationPrincipal AuthenticatedUser user,
                            @RequestParam(value = "error", required = false) String error,
                            @RequestParam(value = "signedUp", required = false) String signedUp,
                            @RequestParam(value = "signedOut", required = false) String signedOut,
                            @RequestParam(value = "expired", required = false) String expired,
                            @RequestParam(value = "returnUrl", required = false) String returnUrl,
                            @RequestParam(value = "stale", required = false) String stale,
                            Model model) {
        if (user != null) {
            return "redirect:" + AuthService.safeReturnUrl(returnUrl);
        }
        if (ReturnUrlSupport.shouldResetLoginUrl(returnUrl)) {
            return "redirect:/login";
        }
        String formReturn = ReturnUrlSupport.loginFormReturnUrl(returnUrl);
        if (formReturn != null) {
            model.addAttribute("returnUrl", formReturn);
        }
        if (error != null) {
            model.addAttribute("error", "Invalid username or password.");
        }
        if (signedUp != null) {
            model.addAttribute("notice", "Account created. Please sign in.");
        }
        if (signedOut != null) {
            model.addAttribute("notice", "You've been signed out.");
        }
        if (expired != null) {
            model.addAttribute("notice", "Your session expired. Please sign in again.");
        }
        if (stale != null) {
            model.addAttribute("error", "This form expired. Refresh the page and try again.");
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam("username") String username,
                        @RequestParam("password") String password,
                        @RequestParam(value = "returnUrl", required = false) String returnUrl,
                        HttpServletRequest request,
                        HttpServletResponse response) {
        String ip = ClientInfo.ip(request);
        String ua = ClientInfo.userAgent(request);
        var userOpt = authService.authenticate(username, password, ip, ua);
        if (userOpt.isEmpty()) {
            String safe = ReturnUrlSupport.loginFormReturnUrl(returnUrl);
            if (safe == null) {
                return "redirect:/login?error=1";
            }
            return "redirect:/login?error=1&returnUrl=" + UriUtils.encodeQueryParam(safe, StandardCharsets.UTF_8);
        }
        UserResponse user = userOpt.get();
        authService.issueSession(user, response);
        authService.recordLoginSuccess(user, ip, ua);
        return "redirect:" + AuthService.safeReturnUrl(returnUrl);
    }

    @GetMapping("/signup")
    public String signupPage(@AuthenticationPrincipal AuthenticatedUser user,
                             @RequestParam(value = "stale", required = false) String stale,
                             Model model) {
        if (user != null) {
            return "redirect:/dashboard";
        }
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
        if (stale != null) {
            model.addAttribute("error", "This form expired. Refresh the page and try again.");
        }
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
            return signupPage(null, null, model);
        }

        try {
            UserResponse user = ledger.signup(req, ClientInfo.ip(request), ClientInfo.userAgent(request));
            authService.issueSession(user, response);
            authService.recordLoginSuccess(user, ClientInfo.ip(request), ClientInfo.userAgent(request));
            return "redirect:/dashboard?welcome=1";
        } catch (UsernameAlreadyExistsException ex) {
            model.addAttribute("error", "That username is already taken. Please pick another.");
            model.addAttribute("signup", new SignupRequest(req.username(), req.displayName(), "", ""));
            return signupPage(null, null, model);
        } catch (RuntimeException ex) {
            log.warn("Signup failed for {}: {}", req.username(), ex.getMessage());
            model.addAttribute("error", "Could not create your account. " + ex.getMessage());
            model.addAttribute("signup", new SignupRequest(req.username(), req.displayName(), "", ""));
            return signupPage(null, null, model);
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
}
