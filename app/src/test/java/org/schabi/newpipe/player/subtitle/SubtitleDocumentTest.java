/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.subtitle;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SubtitleDocumentTest {

    @Test
    public void srtIsConvertedToBilingualWebVtt() throws Exception {
        final SubtitleDocument document = SubtitleDocument.parse("""
                1
                00:00:01,250 --> 00:00:02,500
                こんにちは

                """, MediaFormat.SRT);

        final String result = new String(document.toWebVtt(List.of("Hello"), true),
                StandardCharsets.UTF_8);

        assertEquals("""
                WEBVTT

                00:00:01.250 --> 00:00:02.500
                こんにちは
                Hello

                """, result);
    }

    @Test
    public void vttFormattingTagsAreNotSentToTranslation() throws Exception {
        final SubtitleDocument document = SubtitleDocument.parse("""
                WEBVTT

                cue-id
                00:01.000 --> 00:02.000 align:start
                <i>Hello</i> &amp; welcome

                """, MediaFormat.VTT);

        assertEquals(List.of("Hello & welcome"), document.getTexts());
    }

    @Test
    public void ttmlParagraphsAreConvertedToWebVtt() throws Exception {
        final SubtitleDocument document = SubtitleDocument.parse("""
                <?xml version="1.0" encoding="utf-8"?>
                <tt xmlns="http://www.w3.org/ns/ttml">
                    <body>
                        <div>
                            <p begin="1.5s" dur="2s">猫 &amp; dog</p>
                        </div>
                    </body>
                </tt>
                """, MediaFormat.TTML);

        final String result = new String(document.toWebVtt(List.of("cat & dog"), false),
                StandardCharsets.UTF_8);

        assertEquals("""
                WEBVTT

                00:00:01.500 --> 00:00:03.500
                cat &amp; dog

                """, result);
    }

    @Test
    public void generatedTtmlParagraphsWithMillisecondTimingAreConverted() throws Exception {
        final SubtitleDocument document = SubtitleDocument.parse("""
                <timedtext>
                    <body>
                        <p t="1500" d="2000">generated caption</p>
                    </body>
                </timedtext>
                """, MediaFormat.TTML);

        final String result = new String(document.toWebVtt(List.of("translated"), false),
                StandardCharsets.UTF_8);

        assertEquals("""
                WEBVTT

                00:00:01.500 --> 00:00:03.500
                translated

                """, result);
    }

    @Test
    public void generatedTranscriptXmlIsConverted() throws Exception {
        final SubtitleDocument document = SubtitleDocument.parse("""
                <transcript>
                    <text start="1.5" dur="2.0">generated caption</text>
                </transcript>
                """, MediaFormat.TTML);

        final String result = new String(document.toWebVtt(List.of("translated"), false),
                StandardCharsets.UTF_8);

        assertEquals("""
                WEBVTT

                00:00:01.500 --> 00:00:03.500
                translated

                """, result);
    }

    @Test
    public void progressiveWebVttReturnsMarkedSourceTextImmediately() throws Exception {
        final SubtitleDocument document = SubtitleDocument.parse("""
                <tt>
                    <body>
                        <p begin="1.5s" dur="2s">source caption</p>
                    </body>
                </tt>
                """, MediaFormat.TTML);

        final String result = new String(document.toProgressiveWebVtt("abc123"),
                StandardCharsets.UTF_8);

        assertEquals("""
                WEBVTT

                00:00:01.500 --> 00:00:03.500
                ⁣newpipe-gemini:abc123:0⁣source caption

                """, result);
    }

    @Test
    public void translationBatchIsLimitedBySubtitleDuration() throws Exception {
        final SubtitleDocument document = SubtitleDocument.parse("""
                <tt>
                    <body>
                        <p begin="0s" dur="2s">first</p>
                        <p begin="179s" dur="2s">second</p>
                        <p begin="180s" dur="2s">third</p>
                    </body>
                </tt>
                """, MediaFormat.TTML);

        assertEquals(2, document.getBatchEndIndex(0, 100, 12_000, 180_000));
    }
}
