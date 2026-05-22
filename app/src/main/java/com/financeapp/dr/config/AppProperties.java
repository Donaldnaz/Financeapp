package com.financeapp.dr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String region, Storage storage, Seed seed, Cookie cookie) {

    public record Storage(String type, String tableName) {
    }

    public record Seed(boolean enabled) {
    }

    public record Cookie(boolean secure) {
    }
}
