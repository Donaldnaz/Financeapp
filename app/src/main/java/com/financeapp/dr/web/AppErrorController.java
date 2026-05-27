package com.financeapp.dr.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Map;

/**
 * Handles Spring Boot's {@code /error} endpoint. A direct GET to {@code /error} (for example after
 * login with {@code returnUrl=/error} from a blocked error dispatch) has no servlet error attributes
 * and would otherwise render status {@code 999} / {@code None}.
 */
@Controller
public class AppErrorController implements ErrorController {

    private static final int UNKNOWN_STATUS = 999;

    private final ErrorAttributes errorAttributes;

    public AppErrorController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        Map<String, Object> attributes = errorAttributes.getErrorAttributes(
                new ServletWebRequest(request), ErrorAttributeOptions.of(ErrorAttributeOptions.Include.MESSAGE));

        Object status = attributes.get("status");
        int statusCode = status instanceof Number number ? number.intValue() : UNKNOWN_STATUS;
        if (statusCode == UNKNOWN_STATUS) {
            return "redirect:/login";
        }

        model.addAttribute("status", statusCode);
        model.addAttribute("errorTitle", attributes.getOrDefault("error", "Something went wrong"));
        model.addAttribute("errorMessage", attributes.getOrDefault("message", "Please try again or contact support."));
        return "error";
    }
}
