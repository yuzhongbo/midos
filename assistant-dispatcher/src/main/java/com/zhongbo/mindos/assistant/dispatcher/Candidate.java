package com.zhongbo.mindos.assistant.dispatcher;

public final class Candidate {

    private final String target;
    private final double score;
    private final String source;

    public Candidate(String target, double score, String source) {
        this.target = normalize(target);
        this.score = Math.max(0.0, score);
        this.source = normalize(source);
    }

    public String target() {
        return target;
    }

    public double score() {
        return score;
    }

    public String source() {
        return source;
    }

    public boolean matches(Candidate other) {
        if (other == null) {
            return false;
        }
        return target.equals(other.target())
                && source.equals(other.source())
                && Double.compare(score, other.score()) == 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
