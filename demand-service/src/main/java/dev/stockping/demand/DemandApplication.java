package dev.stockping.demand;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.context.annotation.Import(dev.stockping.security.ApiSecurity.class)
@SpringBootApplication
public class DemandApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemandApplication.class, args);
    }
}
