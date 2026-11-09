package dev.stockping.demand.controller;

import dev.stockping.security.Identity;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final JdbcTemplate jdbc;
    public NotificationController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping
    public List<Map<String, Object>> recent() {
        return jdbc.queryForList("""
                SELECT id,availability_event_id,variant_id,waitlist_id,status,reason,provider_message_id,created_at,authorized_at,completed_at
                FROM notification_jobs WHERE merchant_id=? ORDER BY created_at DESC,id LIMIT 100
                """, Identity.current().merchantId());
    }
}
