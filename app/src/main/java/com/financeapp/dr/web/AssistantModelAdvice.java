package com.financeapp.dr.web;

import com.financeapp.dr.assistant.AssistantPopularQueries;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

@ControllerAdvice
public class AssistantModelAdvice {

    @ModelAttribute("assistantPopularQueries")
    public List<AssistantPopularQueries.PopularQuery> assistantPopularQueries() {
        return AssistantPopularQueries.all();
    }
}
