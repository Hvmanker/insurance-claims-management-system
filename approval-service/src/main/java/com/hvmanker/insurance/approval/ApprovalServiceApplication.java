package com.hvmanker.insurance.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

@SpringBootApplication
public class ApprovalServiceApplication {
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> internalApiFilterRegistration(OncePerRequestFilter internalApiAuthFilter) {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(internalApiAuthFilter);
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(1);
        return registration;
    }

    public static void main(String[] args) {
        SpringApplication.run(ApprovalServiceApplication.class, args);
    }
}

