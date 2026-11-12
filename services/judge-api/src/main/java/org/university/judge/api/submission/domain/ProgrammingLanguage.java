package org.university.judge.api.submission.domain;

public enum ProgrammingLanguage {
    JAVA("java"), PYTHON("py"), C("c"), CPP("cpp"), JAVASCRIPT("js"), TYPESCRIPT("ts"),
    KOTLIN("kt"), GO("go"), RUST("rs"), CSHARP("cs");

    private final String extension;
    ProgrammingLanguage(String extension) { this.extension = extension; }
    public String extension() { return extension; }
    public static ProgrammingLanguage parse(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException | NullPointerException ignored) {
            throw new SubmissionException(SubmissionException.Code.UNSUPPORTED_LANGUAGE);
        }
    }
}
