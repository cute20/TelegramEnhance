package io.github.telegramenhance;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.security.InstanceLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WorkbenchApplication {
    private static InstanceLock processLock;
    public static void main(String[] args) {
        processLock = new InstanceLock(new AppPaths());
        try { SpringApplication.run(WorkbenchApplication.class, args); }
        catch (RuntimeException e) { processLock.close(); throw e; }
    }
    @Bean public InstanceLock instanceLock(AppPaths paths) {
        return processLock == null ? new InstanceLock(paths) : processLock;
    }
}
