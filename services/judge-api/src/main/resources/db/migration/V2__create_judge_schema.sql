CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE CHECK (btrim(username) <> ''),
    email VARCHAR(254) NOT NULL UNIQUE CHECK (btrim(email) <> ''),
    password_hash VARCHAR(255) NOT NULL CHECK (btrim(password_hash) <> ''),
    role VARCHAR(16) NOT NULL CHECK (role IN ('STUDENT', 'TEACHER', 'ADMIN')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (updated_at >= created_at)
);

CREATE TABLE assignments (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    title VARCHAR(255) NOT NULL CHECK (btrim(title) <> ''),
    description TEXT NOT NULL,
    maximum_score NUMERIC(19,4) NOT NULL CHECK (maximum_score >= 0 AND maximum_score <> 'NaN'::numeric),
    deadline TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (updated_at >= created_at)
);
CREATE INDEX assignments_teacher_idx ON assignments(teacher_id);
CREATE INDEX assignments_status_deadline_idx ON assignments(status, deadline);

-- The assignment row is editable. Revisions are the authoritative grading
-- configuration: populate their test cases, seal, then accept submissions.
CREATE TABLE assignment_revisions (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL REFERENCES assignments(id) ON DELETE RESTRICT,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    title VARCHAR(255) NOT NULL CHECK (btrim(title) <> ''),
    description TEXT NOT NULL,
    maximum_score NUMERIC(19,4) NOT NULL CHECK (maximum_score >= 0 AND maximum_score <> 'NaN'::numeric),
    deadline TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sealed_at TIMESTAMPTZ,
    UNIQUE (assignment_id, revision_number),
    UNIQUE (id, assignment_id),
    CHECK (sealed_at IS NULL OR sealed_at >= created_at)
);

CREATE TABLE test_cases (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL REFERENCES assignments(id) ON DELETE RESTRICT,
    assignment_revision_id UUID NOT NULL,
    input_data TEXT NOT NULL,
    expected_output TEXT NOT NULL,
    points NUMERIC(19,4) NOT NULL CHECK (points >= 0 AND points <> 'NaN'::numeric),
    is_hidden BOOLEAN NOT NULL DEFAULT TRUE,
    execution_order INTEGER NOT NULL CHECK (execution_order >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (assignment_revision_id, assignment_id)
        REFERENCES assignment_revisions(id, assignment_id) ON DELETE RESTRICT,
    UNIQUE (assignment_revision_id, execution_order),
    UNIQUE (id, assignment_revision_id)
);
CREATE INDEX test_cases_assignment_idx ON test_cases(assignment_id);

CREATE TABLE submissions (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL REFERENCES assignments(id) ON DELETE RESTRICT,
    assignment_revision_id UUID NOT NULL,
    student_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    source_file_key VARCHAR(1024) NOT NULL CHECK (btrim(source_file_key) <> ''),
    original_filename VARCHAR(255) NOT NULL CHECK (btrim(original_filename) <> ''),
    programming_language VARCHAR(64) NOT NULL CHECK (btrim(programming_language) <> ''),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    FOREIGN KEY (assignment_revision_id, assignment_id)
        REFERENCES assignment_revisions(id, assignment_id) ON DELETE RESTRICT,
    UNIQUE (id, assignment_revision_id),
    CHECK (started_at IS NULL OR started_at >= submitted_at),
    CHECK (completed_at IS NULL OR completed_at >= COALESCE(started_at, submitted_at))
);
CREATE INDEX submissions_assignment_time_idx ON submissions(assignment_id, submitted_at DESC);
CREATE INDEX submissions_student_time_idx ON submissions(student_id, submitted_at DESC);
CREATE INDEX submissions_revision_idx ON submissions(assignment_revision_id);
CREATE INDEX submissions_status_time_idx ON submissions(status, submitted_at);

CREATE TABLE submission_results (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL UNIQUE REFERENCES submissions(id) ON DELETE RESTRICT,
    total_score NUMERIC(19,4) NOT NULL CHECK (total_score >= 0 AND total_score <> 'NaN'::numeric),
    maximum_score NUMERIC(19,4) NOT NULL CHECK (maximum_score >= 0 AND maximum_score <> 'NaN'::numeric),
    passed_tests INTEGER NOT NULL CHECK (passed_tests >= 0),
    total_tests INTEGER NOT NULL CHECK (total_tests >= 0),
    execution_time_ms BIGINT CHECK (execution_time_ms >= 0),
    verdict VARCHAR(32) NOT NULL CHECK (verdict IN ('ACCEPTED', 'WRONG_ANSWER', 'COMPILATION_ERROR',
        'RUNTIME_ERROR', 'TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED', 'INTERNAL_ERROR')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (total_score <= maximum_score),
    CHECK (passed_tests <= total_tests)
);

CREATE TABLE test_case_results (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL,
    test_case_id UUID NOT NULL,
    assignment_revision_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACCEPTED', 'WRONG_ANSWER', 'COMPILATION_ERROR',
        'RUNTIME_ERROR', 'TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED', 'INTERNAL_ERROR')),
    actual_output TEXT,
    execution_time_ms BIGINT CHECK (execution_time_ms >= 0),
    awarded_points NUMERIC(19,4) NOT NULL CHECK (awarded_points >= 0 AND awarded_points <> 'NaN'::numeric),
    FOREIGN KEY (submission_id, assignment_revision_id)
        REFERENCES submissions(id, assignment_revision_id) ON DELETE RESTRICT,
    FOREIGN KEY (test_case_id, assignment_revision_id)
        REFERENCES test_cases(id, assignment_revision_id) ON DELETE RESTRICT,
    UNIQUE (submission_id, test_case_id)
);
CREATE INDEX test_case_results_case_idx ON test_case_results(test_case_id);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL CHECK (btrim(event_type) <> ''),
    event_version INTEGER NOT NULL CHECK (event_version > 0),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    CHECK (published_at IS NULL OR published_at >= created_at)
);
CREATE INDEX outbox_events_unpublished_idx ON outbox_events(created_at, id) WHERE published_at IS NULL;
CREATE INDEX outbox_events_aggregate_idx ON outbox_events(aggregate_id);
