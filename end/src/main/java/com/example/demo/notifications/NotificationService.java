package com.example.demo.notifications;

import com.example.demo.notifications.dto.NotificationDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class NotificationService {
    private final ConcurrentHashMap<Long, List<NotificationDto>> store = new ConcurrentHashMap<>();

    private List<NotificationDto> ensureSeed(Long userId) {
        return store.computeIfAbsent(userId, k -> {
            List<NotificationDto> list = new ArrayList<>();
            list.add(new NotificationDto(1L, "reply", "有人回复了你的帖子", "在《新番讨论》里收到一条回复", false, Instant.now().minusSeconds(3600)));
            list.add(new NotificationDto(2L, "mention", "有人在评论中@了你", "@你 在《编程学习》评论中提及你", false, Instant.now().minusSeconds(7200)));
            list.add(new NotificationDto(3L, "like", "你的帖子收到点赞", "《我的笔记》获得 3 个赞", true, Instant.now().minusSeconds(86400)));
            list.add(new NotificationDto(4L, "system", "系统公告", "欢迎加入 MagicAlbum 社区！", true, Instant.now().minusSeconds(172800)));
            return list;
        });
    }

    public Map<String, Object> list(Long userId, String type, Boolean unread, int page, int size) {
        List<NotificationDto> all = new ArrayList<>(ensureSeed(userId));
        if (type != null && !type.isBlank()) {
            String t = type.toLowerCase();
            all = all.stream().filter(n -> t.equals(String.valueOf(n.getType()).toLowerCase())).collect(Collectors.toList());
        }
        if (unread != null) {
            all = all.stream().filter(n -> unread ? !n.isRead() : true).collect(Collectors.toList());
        }
        int total = all.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<NotificationDto> items = from >= total ? List.of() : all.subList(from, to);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("size", size);
        body.put("total", total);
        return body;
    }

    public NotificationDto markRead(Long userId, Long id) {
        List<NotificationDto> list = ensureSeed(userId);
        for (NotificationDto n : list) {
            if (id != null && id.equals(n.getId())) {
                n.setRead(true);
                return n;
            }
        }
        return null;
    }
}