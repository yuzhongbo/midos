package com.zhongbo.mindos.assistant.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.zhongbo.mindos.assistant")
public class MindOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindOsApplication.class, args);
    }
}

