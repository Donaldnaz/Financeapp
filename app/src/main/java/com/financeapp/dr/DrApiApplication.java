package com.financeapp.dr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DrApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DrApiApplication.class, args);
    }
}
