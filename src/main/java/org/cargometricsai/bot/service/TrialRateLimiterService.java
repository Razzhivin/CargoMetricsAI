package org.cargometricsai.bot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TrialRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(TrialRateLimiterService.class);

    private final int freeTrialLimit;
    private final long adminChatId;
    private final ConcurrentHashMap<Long, AtomicInteger> usageCounts = new ConcurrentHashMap<>();

    public TrialRateLimiterService(
            @Value("${telegram.bot.free-trial-limit}") int freeTrialLimit,
            @Value("${telegram.bot.admin-chat-id}") long adminChatId) {
        this.freeTrialLimit = freeTrialLimit;
        this.adminChatId = adminChatId;
    }

    public boolean canProcess(Long chatId) {
        if (chatId == null) return false;
        if (chatId == adminChatId) return true;
        return getRemaining(chatId) > 0;
    }

    public int incrementAndGet(Long chatId) {
        if (chatId == null) return 0;
        AtomicInteger counter = usageCounts.computeIfAbsent(chatId, k -> new AtomicInteger(0));
        return counter.incrementAndGet();
    }

    public int getRemaining(Long chatId) {
        if (chatId == null) return 0;
        if (chatId == adminChatId) return Integer.MAX_VALUE;
        AtomicInteger counter = usageCounts.get(chatId);
        if (counter == null) return freeTrialLimit;
        int remaining = freeTrialLimit - counter.get();
        return Math.max(0, remaining);
    }

    public int getUsedCount(Long chatId) {
        if (chatId == null) return 0;
        AtomicInteger counter = usageCounts.get(chatId);
        return counter != null ? counter.get() : 0;
    }
}
