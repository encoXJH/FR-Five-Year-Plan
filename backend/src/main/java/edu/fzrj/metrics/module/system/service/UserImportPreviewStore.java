package edu.fzrj.metrics.module.system.service;

import edu.fzrj.metrics.module.system.dto.SystemDtos;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UserImportPreviewStore {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final Map<String, Batch> store = new ConcurrentHashMap<>();
    private final Clock clock;

    public UserImportPreviewStore() {
        this(Clock.systemDefaultZone());
    }

    UserImportPreviewStore(Clock clock) {
        this.clock = clock;
    }

    public String save(Long creatorId, List<SystemDtos.UserImportDraftRow> rows, boolean hasInvalidRows) {
        evictExpired();
        String importId = UUID.randomUUID().toString().replace("-", "");
        Instant now = clock.instant();
        Batch batch = new Batch();
        batch.setImportId(importId);
        batch.setCreatorId(creatorId);
        batch.setCreatedAt(now);
        batch.setExpiresAt(now.plus(TTL));
        batch.setRows(List.copyOf(rows));
        batch.setHasInvalidRows(hasInvalidRows);
        store.put(importId, batch);
        return importId;
    }

    public Batch get(String importId) {
        if (importId == null) {
            return null;
        }
        evictExpired();
        return store.get(importId);
    }

    public void remove(String importId) {
        if (importId != null) {
            store.remove(importId);
        }
    }

    private void evictExpired() {
        Instant now = clock.instant();
        store.entrySet().removeIf(entry -> !entry.getValue().getExpiresAt().isAfter(now));
    }

    @Data
    public static class Batch {
        private String importId;
        private Long creatorId;
        private Instant createdAt;
        private Instant expiresAt;
        private List<SystemDtos.UserImportDraftRow> rows = List.of();
        private boolean hasInvalidRows;
    }
}
