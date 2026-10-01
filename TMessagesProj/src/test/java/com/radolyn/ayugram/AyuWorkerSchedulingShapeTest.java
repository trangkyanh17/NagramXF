package com.radolyn.ayugram;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AyuWorkerSchedulingShapeTest {

    @Test
    public void periodConstantIsGone() {
        try {
            AyuWorker.class.getDeclaredField("PERIOD_MS");
            fail("PERIOD_MS must be removed for one-shot scheduling");
        } catch (NoSuchFieldException expected) {
            // expected
        }
    }

    @Test
    public void workerOwnsOneShotSchedulerField() throws Exception {
        Field field = AyuWorker.class.getDeclaredField("oneShotScheduler");
        assertEquals(RestartableOneShotScheduler.class, field.getType());
    }
}
