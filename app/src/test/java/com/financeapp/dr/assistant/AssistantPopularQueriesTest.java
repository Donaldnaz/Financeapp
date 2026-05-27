package com.financeapp.dr.assistant;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPopularQueriesTest {

    @Test
    void exposesSixUniquePopularQueries() {
        var queries = AssistantPopularQueries.all();

        assertThat(queries).hasSize(6);

        Set<String> labels = new HashSet<>();
        Set<String> messages = new HashSet<>();
        for (AssistantPopularQueries.PopularQuery query : queries) {
            assertThat(labels.add(query.label())).isTrue();
            assertThat(messages.add(query.message())).isTrue();
            assertThat(query.label()).isNotBlank();
            assertThat(query.message()).isNotBlank();
        }
    }
}
