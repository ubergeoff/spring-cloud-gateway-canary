package com.example.userservicea;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
public class UserServiceAApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceAApplication.class, args);
    }
}

@RestController
@RequestMapping("/users")
class UserController {

    @GetMapping("/{id}")
    Map<String, Object> getUser(@PathVariable String id) {
        return Map.of(
            "instance",         "user-service-a",
            "deployment-state", "active",
            "port",             8081,
            "userId",           id,
            "message",          "Hello from the ACTIVE (production) instance"
        );
    }

    @GetMapping
    Map<String, Object> listUsers() {
        return Map.of(
            "instance",         "user-service-a",
            "deployment-state", "active",
            "port",             8081,
            "message",          "Listing users from the ACTIVE (production) instance"
        );
    }
}
