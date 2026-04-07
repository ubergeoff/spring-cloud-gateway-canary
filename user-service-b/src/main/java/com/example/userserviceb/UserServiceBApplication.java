package com.example.userserviceb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
public class UserServiceBApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceBApplication.class, args);
    }
}

@RestController
@RequestMapping("/users")
class UserController {

    @Value("${service.response-delay-ms:0}")
    private long responseDelayMs;

    @GetMapping("/{id}")
    Map<String, Object> getUser(@PathVariable String id) throws InterruptedException {
        if (responseDelayMs > 0) Thread.sleep(responseDelayMs);
        return Map.of(
            "instance",         "user-service-b",
            "deployment-state", "passive",
            "port",             8082,
            "userId",           id,
            "message",          "Hello from the PASSIVE (canary) instance"
        );
    }

    @GetMapping
    Map<String, Object> listUsers() throws InterruptedException {
        if (responseDelayMs > 0) Thread.sleep(responseDelayMs);
        return Map.of(
            "instance",         "user-service-b",
            "deployment-state", "passive",
            "port",             8082,
            "message",          "Listing users from the PASSIVE (canary) instance"
        );
    }
}
