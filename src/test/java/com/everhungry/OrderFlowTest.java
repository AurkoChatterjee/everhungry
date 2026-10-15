package com.everhungry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderFlowTest {

    @Test
    void ownerMovesOrderThroughKitchen() {
        assertTrue(OrderFlow.allowed("PLACED", "ACCEPTED", "owner"));
        assertTrue(OrderFlow.allowed("ACCEPTED", "PREPARING", "owner"));
        assertTrue(OrderFlow.allowed("PREPARING", "READY", "owner"));
    }

    @Test
    void driverHandlesPickupAndDelivery() {
        assertTrue(OrderFlow.allowed("READY", "PICKED_UP", "driver"));
        assertTrue(OrderFlow.allowed("PICKED_UP", "ON_THE_WAY", "driver"));
        assertTrue(OrderFlow.allowed("ON_THE_WAY", "DELIVERED", "driver"));
    }

    @Test
    void wrongRoleIsRejected() {
        assertFalse(OrderFlow.allowed("PLACED", "ACCEPTED", "customer"));
        assertFalse(OrderFlow.allowed("READY", "PICKED_UP", "owner"));
        assertFalse(OrderFlow.allowed("ON_THE_WAY", "DELIVERED", "customer"));
    }

    @Test
    void stepsCannotBeSkippedOrReversed() {
        assertFalse(OrderFlow.allowed("PLACED", "READY", "owner"));
        assertFalse(OrderFlow.allowed("READY", "DELIVERED", "driver"));
        assertFalse(OrderFlow.allowed("DELIVERED", "ON_THE_WAY", "driver"));
    }

    @Test
    void customerCanCancelOnlyBeforeAcceptance() {
        assertTrue(OrderFlow.allowed("PLACED", "CANCELLED", "customer"));
        assertFalse(OrderFlow.allowed("ACCEPTED", "CANCELLED", "customer"));
    }

    @Test
    void driversCanClaimOnlyAcceptedOrders() {
        assertFalse(OrderFlow.claimable("PLACED"));
        assertTrue(OrderFlow.claimable("ACCEPTED"));
        assertTrue(OrderFlow.claimable("READY"));
        assertFalse(OrderFlow.claimable("PICKED_UP"));
    }
}
