package com.kkk.bplugin

import junit.framework.TestCase
import java.math.BigDecimal

class TestnetOrderLifecycleTest : TestCase() {
    fun testSubmissionPartialFillAndTerminalStateAreReconciledByClientId() {
        val ledger = TestnetOrderLedger()
        ledger.submitting("qc-1", "BTCUSDT", PaperOrderSide.BUY, "LIMIT", BigDecimal("0.01"), now = 10)
        assertEquals(ManagedOrderState.SUBMITTING, ledger.find("qc-1")?.state)

        ledger.merge(order("qc-1", "PARTIALLY_FILLED", "0.004", 20))
        assertEquals(ManagedOrderState.PARTIALLY_FILLED, ledger.find("qc-1")?.state)
        assertEquals(BigDecimal("0.004"), ledger.find("qc-1")?.executedQuantity)

        ledger.merge(order("qc-1", "FILLED", "0.01", 30))
        assertEquals(ManagedOrderState.FILLED, ledger.find("qc-1")?.state)
        assertTrue(ledger.active().isEmpty())
    }

    fun testMissingActiveOrderBecomesUncertainAfterGracePeriod() {
        val ledger = TestnetOrderLedger()
        ledger.submitting("qc-2", "ETHUSDT", PaperOrderSide.BUY, "LIMIT", BigDecimal.ONE, now = 1)
        ledger.reconcile(emptyList(), now = 60_002)
        assertEquals(ManagedOrderState.UNCERTAIN, ledger.find("qc-2")?.state)
    }

    fun testEmergencyStopAndCloseOnlyGuardNewOrders() {
        assertNotNull(validateTestnetTradingGuard(false, false, PaperOrderSide.SELL))
        assertNotNull(validateTestnetTradingGuard(true, true, PaperOrderSide.BUY))
        assertNull(validateTestnetTradingGuard(true, true, PaperOrderSide.SELL))
        assertNull(validateTestnetTradingGuard(true, false, PaperOrderSide.BUY))
    }

    fun testFailureClassificationUsesLongerBackoffForRateLimitsAndAuth() {
        assertEquals(TestnetFailureKind.RATE_LIMIT, classifyTestnetFailure(BinanceTestnetException(-1003, "limited")))
        assertEquals(TestnetFailureKind.AUTH, classifyTestnetFailure(BinanceTestnetException(-2015, "bad key")))
        assertTrue(testnetRetryDelayMillis(BinanceTestnetException(-1003, "limited")) >
            testnetRetryDelayMillis(java.io.IOException("offline")))
    }

    private fun order(clientId: String, status: String, executed: String, time: Long) = TestnetOrder(
        42, "BTCUSDT", PaperOrderSide.BUY, PaperOrderType.LIMIT, BigDecimal("0.01"), BigDecimal(executed),
        BigDecimal("60000"), null, status, time, clientOrderId = clientId)
}
