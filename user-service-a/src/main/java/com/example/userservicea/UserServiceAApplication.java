package com.example.userservicea;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@SpringBootApplication
public class UserServiceAApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceAApplication.class, args);
    }

    @Bean
    CommandLineRunner seedData(UserRepository repo) {
        return args -> {
            if (repo.count() == 0) {
                repo.save(new User("Alice", "alice@example.com"));
                repo.save(new User("Bob", "bob@example.com"));
                repo.save(new User("Carol", "carol@example.com"));
            }
        };
    }
}

@Entity
@Table(name = "users")
class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String email;

    protected User() {}

    User(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}

interface UserRepository extends JpaRepository<User, Long> {}

@RestController
@RequestMapping("/users")
class UserController {

    @Value("${service.response-delay-ms:0}")
    private long responseDelayMs;

    private final UserRepository userRepository;

    UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/{id}")
    Map<String, Object> getUser(@PathVariable String id) throws InterruptedException {
        if (responseDelayMs > 0) Thread.sleep(responseDelayMs);
        List<User> users = userRepository.findAll();
        return Map.of(
            "instance",         "user-service-a",
            "deployment-state", "active",
            "port",             8081,
            "userId",           id,
            "message",          "Hello from the ACTIVE (production) instance",
            "dbUsers",          users
        );
    }

    @GetMapping
    Map<String, Object> listUsers() throws InterruptedException {
        if (responseDelayMs > 0) Thread.sleep(responseDelayMs);
        List<User> users = userRepository.findAll();
        return Map.of(
            "instance",         "user-service-a",
            "deployment-state", "active",
            "port",             8081,
            "message",          "Listing users from the ACTIVE (production) instance",
            "dbUsers",          users
        );
    }
}
