package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

public record Budget(double credits,
                     double reservedCredits) {

    public Budget {
        credits = clamp(credits);
        reservedCredits = clamp(Math.min(credits, reservedCredits));
    }

    public double availableCredits() {
        return clamp(credits - reservedCredits);
    }

    public Budget withCredits(double nextCredits) {
        return new Budget(nextCredits, Math.min(nextCredits, reservedCredits));
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value);
    }
}
