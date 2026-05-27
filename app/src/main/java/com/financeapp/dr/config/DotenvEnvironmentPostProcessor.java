package com.financeapp.dr.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "dotenv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = findEnvFile();
        if (envFile == null) {
            return;
        }
        Map<String, Object> values = load(envFile);
        if (values.isEmpty()) {
            return;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (isAppProperty(entry.getKey()) && System.getenv(entry.getKey()) == null) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        if (filtered.isEmpty()) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, filtered));
    }

    private static Path findEnvFile() {
        Path cwd = Path.of("").toAbsolutePath();
        List<Path> candidates = List.of(
                cwd.resolve(".env"),
                cwd.resolve("..").resolve(".env").normalize()
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, Object> load(Path envFile) {
        Map<String, Object> values = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(envFile)) {
            lines.map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(line -> parseLine(line, values));
        } catch (IOException ignored) {
            return Map.of();
        }
        return values;
    }

    static boolean isAppProperty(String key) {
        return key.startsWith("ASSISTANT_")
                || key.startsWith("OPENAI_")
                || key.startsWith("APP_")
                || key.startsWith("GOOGLE_")
                || "SPRING_PROFILES_ACTIVE".equals(key);
    }

    private static void parseLine(String line, Map<String, Object> values) {
        int idx = line.indexOf('=');
        if (idx <= 0) {
            return;
        }
        String key = line.substring(0, idx).trim();
        String value = line.substring(idx + 1).trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        if (!key.isEmpty()) {
            values.put(key, value);
        }
    }
}
