package com.starfield.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class StarfieldApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarfieldApiApplication.class, args);
    }
}
