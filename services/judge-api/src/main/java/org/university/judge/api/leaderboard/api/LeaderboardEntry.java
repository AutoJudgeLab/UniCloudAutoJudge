package org.university.judge.api.leaderboard.api;
import java.math.BigDecimal;
import java.util.UUID;
public record LeaderboardEntry(long rank, String username, BigDecimal bestScore, UUID submissionId) { }

