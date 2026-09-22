package com.med.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WhatsAppMedicalApplication {

    public static void main(String[] args) {
        SpringApplication.run(WhatsAppMedicalApplication.class, args);
    }
}
