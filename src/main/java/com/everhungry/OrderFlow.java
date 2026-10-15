package com.everhungry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The order state machine: which role may move an order from one status to the next. */
public final class OrderFlow {
    public static final List<String> HAPPY_PATH =
            List.of("PLACED", "ACCEPTED", "PREPARING", "READY", "PICKED_UP", "ON_THE_WAY", "DELIVERED");

    private static final Set<String> CLAIMABLE = Set.of("ACCEPTED", "PREPARING", "READY");
    private static final Set<String> FINISHED = Set.of("DELIVERED", "REJECTED", "CANCELLED");
    private static final Map<String, String> ACTOR = new HashMap<>();

    static {
        ACTOR.put("PLACED>ACCEPTED", "owner");
        ACTOR.put("PLACED>REJECTED", "owner");
        ACTOR.put("PLACED>CANCELLED", "customer");
        ACTOR.put("ACCEPTED>PREPARING", "owner");
        ACTOR.put("PREPARING>READY", "owner");
        ACTOR.put("READY>PICKED_UP", "driver");
        ACTOR.put("PICKED_UP>ON_THE_WAY", "driver");
        ACTOR.put("ON_THE_WAY>DELIVERED", "driver");
    }

    private OrderFlow() {}

    public static boolean allowed(String from, String to, String role) {
        return role != null && role.equals(ACTOR.get(from + ">" + to));
    }

    /** A driver can take an order once the kitchen has accepted it, until someone else does. */
    public static boolean claimable(String status) {
        return CLAIMABLE.contains(status);
    }

    public static boolean finished(String status) {
        return FINISHED.contains(status);
    }
}
