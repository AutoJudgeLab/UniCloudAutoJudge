package org.university.judge.api.leaderboard.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.university.judge.api.leaderboard.api.*;

@Repository
public class LeaderboardRepository {
    // Validate legacy summaries as well as new completion-service reports. No Java-side ranking.
    private static final String BEST = """
        WITH eligible AS (
            SELECT s.id, s.student_id, s.completed_at, r.total_score,
                row_number() OVER (PARTITION BY s.student_id ORDER BY r.total_score DESC, s.completed_at, s.id) AS choice
            FROM submissions s JOIN submission_results r ON r.submission_id = s.id
            JOIN assignment_revisions revision ON revision.id = s.assignment_revision_id
            CROSS JOIN LATERAL (
                SELECT count(*) AS count, count(*) FILTER (WHERE t.status = 'ACCEPTED') AS passed,
                    COALESCE(sum(t.awarded_points), 0) AS score,
                    COALESCE(bool_and(t.status <> 'INTERNAL_ERROR' AND
                        t.awarded_points = CASE WHEN t.status = 'ACCEPTED' THEN c.points ELSE 0 END), true) AS valid,
                    COALESCE(bool_or(t.status = r.verdict), false) AS matches_verdict
                FROM test_case_results t JOIN test_cases c ON c.id = t.test_case_id
                WHERE t.submission_id = s.id
            ) outcomes
            WHERE s.assignment_id = ? AND s.status = 'COMPLETED' AND s.completed_at IS NOT NULL
                AND r.verdict <> 'INTERNAL_ERROR' AND r.maximum_score = revision.maximum_score
                AND r.total_tests > 0
                AND r.total_tests = (SELECT count(*) FROM test_cases c WHERE c.assignment_revision_id = revision.id)
                AND outcomes.valid AND outcomes.score = r.total_score AND outcomes.passed = r.passed_tests
                AND ((r.verdict = 'COMPILATION_ERROR' AND outcomes.count = 0)
                    OR (outcomes.count = r.total_tests AND
                        ((r.verdict = 'ACCEPTED' AND outcomes.passed = r.total_tests)
                            OR (r.verdict NOT IN ('ACCEPTED', 'COMPILATION_ERROR') AND outcomes.passed < r.total_tests AND outcomes.matches_verdict))))
        ), best AS (SELECT * FROM eligible WHERE choice = 1)
        """;
    private final JdbcTemplate jdbc;
    public LeaderboardRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public LeaderboardPage find(UUID assignmentId, LeaderboardRequest request) {
        long total = jdbc.queryForObject(BEST + "SELECT count(*) FROM best", Long.class, assignmentId);
        var entries = jdbc.query(BEST + """
                SELECT row_number() OVER (ORDER BY b.total_score DESC, b.completed_at, b.id, b.student_id) AS rank,
                    u.username, b.total_score, b.id
                FROM best b JOIN users u ON u.id = b.student_id
                ORDER BY rank LIMIT ? OFFSET ?
                """, (row, index) -> new LeaderboardEntry(row.getLong("rank"), row.getString("username"),
                        row.getBigDecimal("total_score"), row.getObject("id", UUID.class)),
                assignmentId, request.size(), (long) request.page() * request.size());
        return new LeaderboardPage(entries, request.page(), request.size(), total, (total + request.size() - 1) / request.size());
    }
}

