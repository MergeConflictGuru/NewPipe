/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.subtitle;

import org.junit.Test;

import java.util.List;

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

    @Test
    public void uniqueAlignmentsAreAccepted() {
        final List<GeminiSubtitleDataSource.Alignment> alignments =
                GeminiSubtitleDataSource.parseAlignments(
                "[[\"chat\",\"cat\"],[\"vite\",\"fast\"]]", "chat vite", "cat fast");

        assertEquals(2, alignments.size());
        assertEquals(0, alignments.get(0).sourceOccurrence);
        assertEquals(0, alignments.get(0).targetOccurrence);
        assertEquals(0, alignments.get(1).sourceOccurrence);
        assertEquals(0, alignments.get(1).targetOccurrence);
    }

    @Test
    public void alignmentUsesFirstRepeatedSourceSubstring() {
        final List<GeminiSubtitleDataSource.Alignment> alignments =
                GeminiSubtitleDataSource.parseAlignments(
                "[[\"chat\",\"cat\"]]", "chat et chat", "cat");

        assertEquals(1, alignments.size());
        assertEquals(0, alignments.get(0).sourceOccurrence);
    }

    @Test
    public void alignmentUsesFirstRepeatedTargetSubstring() {
        final List<GeminiSubtitleDataSource.Alignment> alignments =
                GeminiSubtitleDataSource.parseAlignments(
                "[[\"chat\",\"cat\"]]", "chat", "cat and cat");

        assertEquals(1, alignments.size());
        assertEquals(0, alignments.get(0).targetOccurrence);
    }

    @Test
    public void repeatedAlignmentsConsumeOccurrencesInReadingOrder() {
        final List<GeminiSubtitleDataSource.Alignment> alignments =
                GeminiSubtitleDataSource.parseAlignments(
                "[[\"steak\",\"steak\"],[\"tofu\",\"tofu\"],[\"steak\",\"steak\"]]",
                "steak tofu steak", "steak tofu steak");

        assertEquals(3, alignments.size());
        assertEquals(0, alignments.get(0).sourceOccurrence);
        assertEquals(0, alignments.get(0).targetOccurrence);
        assertEquals(0, alignments.get(1).sourceOccurrence);
        assertEquals(0, alignments.get(1).targetOccurrence);
        assertEquals(1, alignments.get(2).sourceOccurrence);
        assertEquals(1, alignments.get(2).targetOccurrence);
    }

    @Test
    public void alignmentsSkipOverlappingPairs() {
        final List<GeminiSubtitleDataSource.Alignment> alignments =
                GeminiSubtitleDataSource.parseAlignments(
                "[[\"chat noir\",\"black cat\"],[\"chat\",\"cat\"]]",
                "chat noir", "black cat");

        assertEquals(1, alignments.size());
        assertEquals("chat noir", alignments.get(0).source);
        assertEquals("black cat", alignments.get(0).target);
    }

    @Test
    public void minuteRateLimitCanBeRetried() {
        assertEquals(true, GeminiSubtitleDataSource.isRetryableGeminiRateLimit(
                "quotaId: GenerateRequestsPerMinutePerProjectPerModel-FreeTier"));
    }

    @Test
    public void dailyRateLimitMustNotBeRetried() {
        assertEquals(false, GeminiSubtitleDataSource.isRetryableGeminiRateLimit(
                "quotaId: GenerateRequestsPerDayPerProjectPerModel-FreeTier"));
    }

    @Test
    public void dailyQuotaVariantsAreDetected() {
        assertEquals(true, GeminiSubtitleDataSource.isDailyGeminiQuota(
                "QuotaFailure: GenerateTokensPerDayPerProjectPerModel-FreeTier"));
        assertEquals(true, GeminiSubtitleDataSource.isDailyGeminiQuota(
                "You have exceeded your daily quota"));
        assertEquals(true, GeminiSubtitleDataSource.isDailyGeminiQuota(
                "error code: quota_exceeded"));
        assertEquals(false, GeminiSubtitleDataSource.isDailyGeminiQuota(
                "quotaId: GenerateRequestsPerMinutePerProjectPerModel-FreeTier"));
    }
}
