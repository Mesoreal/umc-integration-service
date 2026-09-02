package ru.provless.umc.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables @Async for AmoCrmLeadService — deal creation must not block the precheck HTTP response. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
