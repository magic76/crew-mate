package com.crewpocket.mate.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TurnTextAccumulatorTest {
    @Test
    public void joinsChineseChunksWithoutArtificialSpaces() {
        TurnTextAccumulator accumulator = new TurnTextAccumulator();
        accumulator.append("好的");
        accumulator.append("我幫你");
        accumulator.append("問看看");
        assertEquals("好的我幫你問看看", accumulator.take());
        assertTrue(accumulator.isEmpty());
    }

    @Test
    public void joinsLatinWordChunksWithSpaces() {
        TurnTextAccumulator accumulator = new TurnTextAccumulator();
        accumulator.append("I");
        accumulator.append("can");
        accumulator.append("help");
        accumulator.append("you.");
        assertEquals("I can help you.", accumulator.take());
    }

    @Test
    public void doesNotInsertSpaceBeforePunctuation() {
        TurnTextAccumulator accumulator = new TurnTextAccumulator();
        accumulator.append("Sounds");
        accumulator.append("good");
        accumulator.append("!");
        assertEquals("Sounds good!", accumulator.take());
    }

    @Test
    public void cumulativePartialTranscriptReplacesEarlierPartial() {
        TurnTextAccumulator accumulator = new TurnTextAccumulator();
        accumulator.append("幫我問 John");
        accumulator.append("幫我問 John 明天晚上");
        accumulator.append("幫我問 John 明天晚上有沒有空");
        assertEquals("幫我問 John 明天晚上有沒有空", accumulator.take());
    }

    @Test
    public void overlappingChunksDoNotDuplicateText() {
        TurnTextAccumulator accumulator = new TurnTextAccumulator();
        accumulator.append("hello wor");
        accumulator.append("world!");
        assertEquals("hello world!", accumulator.take());
    }
}
