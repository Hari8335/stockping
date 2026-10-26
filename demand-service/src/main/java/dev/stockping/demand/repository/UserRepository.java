package dev.stockping.demand.repository;
import dev.stockping.demand.domain.User;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}
