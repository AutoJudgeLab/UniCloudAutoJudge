package org.university.judge.api.leaderboard.api;

import java.util.List;

public record LeaderboardPage(List<LeaderboardEntry> content, int page, int size, long totalElements, long totalPages) {
}

