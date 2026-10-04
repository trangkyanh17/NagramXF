package tw.nekomimi.nekogram.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AyuFilterPrewarmCoordinatorTest {

    @Test
    public void disabledRequestDoesNotSchedule() {
        AyuFilterPrewarmCoordinator coordinator = new AyuFilterPrewarmCoordinator();

        assertEquals(-1L, coordinator.trySchedule(false));
        assertEquals(0L, coordinator.currentGeneration());
    }

    @Test
    public void enabledFirstRequestSchedulesCurrentGeneration() {
        AyuFilterPrewarmCoordinator coordinator = new AyuFilterPrewarmCoordinator();

        long token = coordinator.trySchedule(true);

        assertEquals(0L, token);
        assertTrue(coordinator.isCurrent(token));
    }

    @Test
    public void duplicateRequestInSameGenerationIsCoalesced() {
        AyuFilterPrewarmCoordinator coordinator = new AyuFilterPrewarmCoordinator();

        assertEquals(0L, coordinator.trySchedule(true));
        assertEquals(-1L, coordinator.trySchedule(true));
    }

    @Test
    public void invalidationAdvancesGenerationAndAllowsNewSchedule() {
        AyuFilterPrewarmCoordinator coordinator = new AyuFilterPrewarmCoordinator();
        long oldToken = coordinator.trySchedule(true);

        long generation = coordinator.invalidate();

        assertEquals(1L, generation);
        assertFalse(coordinator.isCurrent(oldToken));
        assertEquals(1L, coordinator.trySchedule(true));
    }

    @Test
    public void failureForCurrentGenerationReopensScheduling() {
        AyuFilterPrewarmCoordinator coordinator = new AyuFilterPrewarmCoordinator();
        long token = coordinator.trySchedule(true);

        coordinator.onFailure(token);

        assertEquals(0L, coordinator.trySchedule(true));
    }

    @Test
    public void staleFailureCannotReopenNewGeneration() {
        AyuFilterPrewarmCoordinator coordinator = new AyuFilterPrewarmCoordinator();
        long oldToken = coordinator.trySchedule(true);
        coordinator.invalidate();
        long currentToken = coordinator.trySchedule(true);

        coordinator.onFailure(oldToken);

        assertEquals(1L, currentToken);
        assertEquals(-1L, coordinator.trySchedule(true));
    }

    @Test
    public void successfulCurrentScheduleRemainsCoalescedUntilInvalidation() {
        AyuFilterPrewarmCoordinator coordinator = new AyuFilterPrewarmCoordinator();
        long token = coordinator.trySchedule(true);

        assertTrue(coordinator.isCurrent(token));
        assertEquals(-1L, coordinator.trySchedule(true));

        coordinator.invalidate();

        assertEquals(1L, coordinator.trySchedule(true));
    }
}
