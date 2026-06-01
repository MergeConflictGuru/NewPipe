/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.subtitle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GeminiSubtitleDataSourceTest {

    @Test
    public void occurrenceIndexDistinguishesRepeatedWords() {
        assertEquals(0, GeminiSubtitleDataSource.findOccurrence("cat cat", "cat", 0, false));
        assertEquals(4, GeminiSubtitleDataSource.findOccurrence("cat cat", "cat", 1, false));
        assertEquals(-1, GeminiSubtitleDataSource.findOccurrence("cat cat", "cat", 2, false));
    }

    @Test
    public void occurrenceLookupCanIgnoreTargetCase() {
        assertEquals(4, GeminiSubtitleDataSource.findOccurrence("Cat CAT", "cat", 1, true));
    }
}
