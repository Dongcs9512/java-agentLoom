package com.aia.core.session;

public record RouteResult(RouteDecision decision, String reply) {

    public static RouteResult canned(String reply) {
        return new RouteResult(RouteDecision.CANNED, reply);
    }

    public static RouteResult direct() {
        return new RouteResult(RouteDecision.DIRECT, null);
    }

    public static RouteResult retrieve() {
        return new RouteResult(RouteDecision.RETRIEVE, null);
    }
}
