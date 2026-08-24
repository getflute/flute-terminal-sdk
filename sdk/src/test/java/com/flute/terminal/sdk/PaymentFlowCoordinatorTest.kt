package com.flute.terminal.sdk

import com.flute.terminal.sdk.callback.PaymentResultCallback
import com.flute.terminal.sdk.model.ErrorReason
import com.flute.terminal.sdk.model.PaymentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A payment must still reach the ISV's callback when the SDK graph the coordinator was registered
 * against is gone — `FluteTerminal.shutdown()`, or an `initialize()` with changed config, cancels
 * that graph's scope. Delivering through it meant the callback silently never fired and the single
 * payment slot stayed claimed forever, so the next attempt reported ALREADY_IN_PROGRESS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentFlowCoordinatorTest {

    private val received = mutableListOf<PaymentResult>()
    private val callback = PaymentResultCallback { received += it }

    /** Worst case: no graph at all. Delivery and slot release must not depend on one. */
    private lateinit var coordinator: PaymentFlowCoordinator

    @Before
    fun setUp() {
        // Before constructing the coordinator: it captures the main dispatcher for callback delivery.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coordinator = PaymentFlowCoordinator(graphProvider = { null }, callback = callback)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `pre-launch failure is delivered without a live graph`() {
        assertTrue(coordinator.tryBeginAttempt())

        coordinator.deliverPreLaunchFailure(
            PaymentResult.Error(ErrorReason.TRANSACTION_CREATION_FAILED, "create failed"),
        )

        assertEquals(1, received.size)
        assertEquals(ErrorReason.TRANSACTION_CREATION_FAILED, (received.single() as PaymentResult.Error).reason)
    }

    @Test
    fun `delivering frees the payment slot for the next attempt`() {
        assertTrue(coordinator.tryBeginAttempt())
        coordinator.deliverPreLaunchFailure(PaymentResult.Error(ErrorReason.UNKNOWN, "boom"))

        assertTrue("a delivered attempt must not hold the slot", coordinator.tryBeginAttempt())
    }

    @Test
    fun `a shut-down SDK reports NOT_INITIALIZED and holds no slot`() {
        assertTrue(coordinator.tryBeginAttempt())

        coordinator.deliverNotInitialized()

        assertEquals(ErrorReason.NOT_INITIALIZED, (received.single() as PaymentResult.Error).reason)
        assertTrue(coordinator.tryBeginAttempt())
    }

    @Test
    fun `an outcome is delivered exactly once`() {
        coordinator.tryBeginAttempt()
        coordinator.deliverPreLaunchFailure(PaymentResult.Error(ErrorReason.UNKNOWN, "first"))
        coordinator.deliverPreLaunchFailure(PaymentResult.Error(ErrorReason.UNKNOWN, "second"))

        assertEquals(1, received.size)
        assertEquals("first", (received.single() as PaymentResult.Error).message)
    }

    @Test
    fun `pre-launch failure carrying an orphaned transaction id still delivers without a live graph`() {
        // The orphan cancel is best-effort and needs a graph; with none, delivery (and the id the
        // ISV needs for a manual cancel) must still reach the callback.
        coordinator.tryBeginAttempt()

        coordinator.deliverPreLaunchFailure(
            PaymentResult.Error(ErrorReason.UNKNOWN, "launcher was unregistered", "pos-123"),
        )

        val error = received.single() as PaymentResult.Error
        assertEquals("pos-123", error.posTransactionId)
        assertTrue(coordinator.tryBeginAttempt())
    }
}
