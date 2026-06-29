package com.llm.okf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication
public class LlmOkfApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmOkfApplication.class, args);
    }

}
