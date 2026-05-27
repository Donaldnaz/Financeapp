package com.financeapp.dr.web;

import com.financeapp.dr.config.AppProperties;
import com.financeapp.dr.model.AccountResponse;
import com.financeapp.dr.model.AuditEventResponse;
import com.financeapp.dr.model.PageResult;
import com.financeapp.dr.model.PaymentMethodType;
import com.financeapp.dr.model.TransactionResponse;
import com.financeapp.dr.security.AuthenticatedUser;
import com.financeapp.dr.service.InsufficientFundsException;
import com.financeapp.dr.service.LedgerService;
import com.financeapp.dr.service.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Controller
public class WebController {

    private final LedgerService ledger;
    private final AppProperties properties;

    public WebController(LedgerService ledger, AppProperties properties) {
        this.ledger = ledger;
        this.properties = properties;
    }

    @ModelAttribute("brand")
    public String brand() {
        return "iTrust";
    }

    @ModelAttribute("region")
    public String region() {
        return properties.region();
    }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user != null) {
            return "redirect:/dashboard";
        }
        return "index";
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AuthenticatedUser user,
                            @RequestParam(value = "welcome", required = false) String welcome,
                            @RequestParam(value = "transferred", required = false) String transferred,
                            @RequestParam(value = "deposited", required = false) String deposited,
                            @RequestParam(value = "withdrawn", required = false) String withdrawn,
                            @RequestParam(value = "stale", required = false) String stale,
                            Model model) {
        AccountResponse account = ledger.getAccount(user.accountId());
        PageResult<TransactionResponse> recent = ledger.listTransactionsForAccount(user.accountId(), null, 10);
        model.addAttribute("user", user);
        model.addAttribute("account", account);
        model.addAttribute("transactions", recent.items());
        if (welcome != null) {
            model.addAttribute("notice", "Welcome aboard. We added a $1,000 welcome bonus to your new account.");
        } else if (transferred != null) {
            model.addAttribute("notice", "Transfer sent.");
        } else if (deposited != null) {
            model.addAttribute("notice", "Deposit posted from your demo card.");
        } else if (withdrawn != null) {
            model.addAttribute("notice", "Withdrawal sent to PayPal.");
        } else if (stale != null) {
            model.addAttribute("error", staleFormMessage());
        }
        return "dashboard";
    }

    @GetMapping("/accounts/{accountId}")
    public String account(@PathVariable("accountId") String accountId,
                          @RequestParam(value = "cursor", required = false) String cursor,
                          @AuthenticationPrincipal AuthenticatedUser user,
                          Model model) {
        ensureOwner(user, accountId);
        AccountResponse account = ledger.getAccount(accountId);
        PageResult<TransactionResponse> page = ledger.listTransactionsForAccount(accountId, cursor, 25);
        model.addAttribute("user", user);
        model.addAttribute("account", account);
        model.addAttribute("page", page);
        return "account";
    }

    @GetMapping("/transfer")
    public String transferForm(@AuthenticationPrincipal AuthenticatedUser user,
                               @RequestParam(value = "stale", required = false) String stale,
                               Model model) {
        AccountResponse account = ledger.getAccount(user.accountId());
        List<String> recipients = ledger.listSeededUsernames().stream()
                .filter(u -> !u.equalsIgnoreCase(user.username()))
                .toList();
        model.addAttribute("user", user);
        model.addAttribute("account", account);
        model.addAttribute("recipients", recipients);
        applyStaleMessage(stale, model);
        return "transfer";
    }

    @PostMapping("/transfer")
    public String submitTransfer(@RequestParam("toUsername") String toUsername,
                                 @RequestParam("amount") BigDecimal amount,
                                 @RequestParam(value = "memo", required = false) String memo,
                                 @AuthenticationPrincipal AuthenticatedUser user,
                                 HttpServletRequest request,
                                 Model model) {
        String normalizedRecipient = UsernameInput.normalizeRecipient(toUsername);
        if (normalizedRecipient.isBlank()) {
            return reTransfer(user, "Please choose a recipient.", model);
        }
        if (normalizedRecipient.equalsIgnoreCase(user.username())) {
            return reTransfer(user, "You can't transfer to yourself.", model);
        }
        if (amount == null || amount.compareTo(new BigDecimal("0.01")) < 0) {
            return reTransfer(user, "Amount must be at least 0.01.", model);
        }
        try {
            ledger.transfer(UUID.randomUUID().toString(), user.accountId(), user.userId(), user.username(),
                    normalizedRecipient, amount, memo, ClientInfo.ip(request), ClientInfo.userAgent(request));
            return "redirect:/dashboard?transferred=1";
        } catch (InsufficientFundsException ife) {
            return reTransfer(user, "Insufficient funds. You don't have enough to send that amount.", model);
        } catch (UserNotFoundException unfe) {
            return reTransfer(user, "Recipient not found.", model);
        } catch (IllegalArgumentException iae) {
            return reTransfer(user, iae.getMessage(), model);
        }
    }

    private String reTransfer(AuthenticatedUser user, String error, Model model) {
        AccountResponse account = ledger.getAccount(user.accountId());
        List<String> recipients = ledger.listSeededUsernames().stream()
                .filter(u -> !u.equalsIgnoreCase(user.username()))
                .toList();
        model.addAttribute("user", user);
        model.addAttribute("account", account);
        model.addAttribute("recipients", recipients);
        model.addAttribute("error", error);
        return "transfer";
    }

    @GetMapping("/deposit")
    public String depositForm(@AuthenticationPrincipal AuthenticatedUser user,
                              @RequestParam(value = "stale", required = false) String stale,
                              Model model) {
        model.addAttribute("user", user);
        model.addAttribute("account", ledger.getAccount(user.accountId()));
        model.addAttribute("demoCard", ledger.getDemoCard(user.userId()).orElse(null));
        applyStaleMessage(stale, model);
        return "deposit";
    }

    @PostMapping("/deposit")
    public String submitDeposit(@RequestParam("amount") BigDecimal amount,
                                @RequestParam(value = "description", required = false) String description,
                                @AuthenticationPrincipal AuthenticatedUser user,
                                HttpServletRequest request,
                                Model model) {
        if (amount == null || amount.compareTo(new BigDecimal("0.01")) < 0) {
            return reDeposit(user, "Amount must be at least 0.01.", model);
        }
        try {
            ledger.deposit(UUID.randomUUID().toString(), user.accountId(), user.userId(), user.username(),
                    amount, description == null || description.isBlank() ? "Deposit" : description,
                    PaymentMethodType.DEMO_CARD,
                    ClientInfo.ip(request), ClientInfo.userAgent(request));
            return "redirect:/dashboard?deposited=1";
        } catch (Exception ex) {
            return reDeposit(user, ex.getMessage(), model);
        }
    }

    private String reDeposit(AuthenticatedUser user, String error, Model model) {
        model.addAttribute("user", user);
        model.addAttribute("account", ledger.getAccount(user.accountId()));
        model.addAttribute("demoCard", ledger.getDemoCard(user.userId()).orElse(null));
        model.addAttribute("error", error);
        return "deposit";
    }

    @GetMapping("/withdraw")
    public String withdrawForm(@AuthenticationPrincipal AuthenticatedUser user,
                               @RequestParam(value = "stale", required = false) String stale,
                               Model model) {
        model.addAttribute("user", user);
        model.addAttribute("account", ledger.getAccount(user.accountId()));
        model.addAttribute("demoPayPal", ledger.getDemoPayPal(user.userId()).orElse(null));
        applyStaleMessage(stale, model);
        return "withdraw";
    }

    @PostMapping("/withdraw")
    public String submitWithdraw(@RequestParam("amount") BigDecimal amount,
                                 @RequestParam("paypalEmail") String paypalEmail,
                                 @RequestParam(value = "description", required = false) String description,
                                 @AuthenticationPrincipal AuthenticatedUser user,
                                 HttpServletRequest request,
                                 Model model) {
        if (paypalEmail == null || paypalEmail.isBlank()) {
            return reWithdraw(user, "PayPal email is required.", model);
        }
        if (amount == null || amount.compareTo(new BigDecimal("0.01")) < 0) {
            return reWithdraw(user, "Amount must be at least 0.01.", model);
        }
        try {
            ledger.withdraw(UUID.randomUUID().toString(), user.accountId(), user.userId(), user.username(),
                    amount, description == null || description.isBlank() ? "Withdrawal to PayPal" : description,
                    paypalEmail.trim(),
                    ClientInfo.ip(request), ClientInfo.userAgent(request));
            return "redirect:/dashboard?withdrawn=1";
        } catch (InsufficientFundsException ife) {
            return reWithdraw(user, "Insufficient funds for this withdrawal.", model);
        } catch (Exception ex) {
            return reWithdraw(user, ex.getMessage(), model);
        }
    }

    private String reWithdraw(AuthenticatedUser user, String error, Model model) {
        model.addAttribute("user", user);
        model.addAttribute("account", ledger.getAccount(user.accountId()));
        model.addAttribute("demoPayPal", ledger.getDemoPayPal(user.userId()).orElse(null));
        model.addAttribute("error", error);
        return "withdraw";
    }

    @GetMapping("/audit")
    public String audit(@AuthenticationPrincipal AuthenticatedUser user,
                        @RequestParam(value = "cursor", required = false) String cursor,
                        Model model) {
        PageResult<AuditEventResponse> page = ledger.listAuditEvents(user.userId(), cursor, 50);
        model.addAttribute("user", user);
        model.addAttribute("page", page);
        model.addAttribute("account", ledger.getAccount(user.accountId()));
        return "audit";
    }

    private void ensureOwner(AuthenticatedUser user, String accountId) {
        if (!accountId.equals(user.accountId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your account");
        }
    }

    private static void applyStaleMessage(String stale, Model model) {
        if (stale != null && !model.containsAttribute("error")) {
            model.addAttribute("error", staleFormMessage());
        }
    }

    private static String staleFormMessage() {
        return "This form expired. Refresh the page and try again.";
    }
}
