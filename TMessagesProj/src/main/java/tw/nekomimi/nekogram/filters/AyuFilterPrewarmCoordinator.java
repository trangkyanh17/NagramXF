package tw.nekomimi.nekogram.filters;

final class AyuFilterPrewarmCoordinator {

    private long generation;
    private long scheduledGeneration = -1L;

    synchronized long trySchedule(boolean enabled) {
        if (!enabled || scheduledGeneration == generation) {
            return -1L;
        }
        scheduledGeneration = generation;
        return generation;
    }

    synchronized long invalidate() {
        generation++;
        scheduledGeneration = -1L;
        return generation;
    }

    synchronized boolean isCurrent(long token) {
        return token == generation;
    }

    synchronized void onFailure(long token) {
        if (token == generation && scheduledGeneration == token) {
            scheduledGeneration = -1L;
        }
    }

    synchronized long currentGeneration() {
        return generation;
    }
}
