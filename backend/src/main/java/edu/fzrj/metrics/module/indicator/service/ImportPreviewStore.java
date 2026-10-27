package edu.fzrj.metrics.module.indicator.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导入预览暂存（importId → 解析结果）。
 * 阶段 2 采用内存暂存（TTL 30 分钟），单实例部署可用；后续阶段可替换为 Redis / 数据库。
 */
@Component
public class ImportPreviewStore {

    private static final long TTL_MINUTES = 30;

    private final Map<String, ParsedImport> store = new ConcurrentHashMap<>();

    public String save(ParsedImport draft) {
        evictExpired();
        String importId = UUID.randomUUID().toString().replace("-", "");
        draft.setImportId(importId);
        draft.setCreatedAt(LocalDateTime.now());
        store.put(importId, draft);
        return importId;
    }

    public ParsedImport get(String importId) {
        evictExpired();
        return store.get(importId);
    }

    public void remove(String importId) {
        store.remove(importId);
    }

    private void evictExpired() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(TTL_MINUTES);
        Iterator<Map.Entry<String, ParsedImport>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().getCreatedAt().isBefore(deadline)) {
                it.remove();
            }
        }
    }
}
