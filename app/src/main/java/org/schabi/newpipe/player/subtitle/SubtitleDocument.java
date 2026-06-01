/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.subtitle;

import androidx.annotation.NonNull;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.schabi.newpipe.extractor.MediaFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal subtitle document used to create translated WebVTT tracks.
 */
final class SubtitleDocument {
    private static final Pattern TIMING_LINE = Pattern.compile(
            "^\\s*(\\S+)\\s+-->\\s+(\\S+).*$");
    private static final Pattern INLINE_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("[\\t\\x0B\\f\\r ]+");
    private static final String GEMINI_CUE_MARKER = "\u2063newpipe-gemini:";

    private final List<CueLine> cues;

    private SubtitleDocument(@NonNull final List<CueLine> cues) {
        this.cues = cues;
    }

    @NonNull
    static SubtitleDocument parse(@NonNull final String subtitleText,
                                  @NonNull final MediaFormat mediaFormat) throws IOException {
        final SubtitleDocument document;
        switch (mediaFormat) {
            case TTML:
                document = parseTtml(subtitleText);
                break;
            case VTT:
            case SRT:
                document = parseTimedText(subtitleText);
                break;
            default:
                throw new IOException("Unsupported subtitle format: " + mediaFormat);
        }

        if (document.cues.isEmpty()) {
            throw new IOException("Subtitle document did not contain any timed text");
        }
        return document;
    }

    static boolean isSupported(@NonNull final MediaFormat mediaFormat) {
        return mediaFormat == MediaFormat.TTML
                || mediaFormat == MediaFormat.VTT
                || mediaFormat == MediaFormat.SRT;
    }

    @NonNull
    List<String> getTexts() {
        final List<String> texts = new ArrayList<>(cues.size());
        for (final CueLine cue : cues) {
            texts.add(cue.text);
        }
        return texts;
    }

    int getCueCount() {
        return cues.size();
    }

    @NonNull
    List<String> getTexts(final int fromIndex, final int toIndex) {
        final List<String> texts = new ArrayList<>(toIndex - fromIndex);
        for (int i = fromIndex; i < toIndex; i++) {
            texts.add(cues.get(i).text);
        }
        return texts;
    }

    int getBatchEndIndex(final int fromIndex, final int maxLines,
                         final int maxCharacters, final long maxDurationMillis) {
        final long firstCueStartMillis = cues.get(fromIndex).startMillis;
        int characterCount = 0;
        int toIndex = fromIndex;
        while (toIndex < cues.size() && toIndex - fromIndex < maxLines) {
            final CueLine cue = cues.get(toIndex);
            if (toIndex > fromIndex
                    && (characterCount + cue.text.length() > maxCharacters
                    || cue.startMillis - firstCueStartMillis >= maxDurationMillis)) {
                break;
            }
            characterCount += cue.text.length();
            toIndex++;
        }
        return toIndex;
    }

    @NonNull
    byte[] toProgressiveWebVtt(@NonNull final String sessionId) {
        final StringBuilder output = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < cues.size(); i++) {
            final CueLine cue = cues.get(i);
            output.append(formatTimestamp(cue.startMillis))
                    .append(" --> ")
                    .append(formatTimestamp(cue.endMillis))
                    .append('\n')
                    .append(GEMINI_CUE_MARKER)
                    .append(sessionId)
                    .append(':')
                    .append(i)
                    .append('\u2063')
                    .append(escapeWebVtt(cue.text))
                    .append("\n\n");
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    byte[] toWebVtt(@NonNull final List<String> translations, final boolean showOriginal) {
        final StringBuilder output = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < cues.size(); i++) {
            final CueLine cue = cues.get(i);
            final String translation = i < translations.size() && !translations.get(i).isBlank()
                    ? translations.get(i) : cue.text;

            output.append(formatTimestamp(cue.startMillis))
                    .append(" --> ")
                    .append(formatTimestamp(cue.endMillis))
                    .append('\n');
            if (showOriginal && !cue.text.equals(translation)) {
                output.append(escapeWebVtt(cue.text)).append('\n');
            }
            output.append(escapeWebVtt(translation)).append("\n\n");
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    private static SubtitleDocument parseTimedText(@NonNull final String subtitleText)
            throws IOException {
        final String[] lines = subtitleText.replace("\r\n", "\n").replace('\r', '\n')
                .split("\n", -1);
        final List<CueLine> cues = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            final Matcher matcher = TIMING_LINE.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }

            final long startMillis = parseTimestamp(matcher.group(1));
            final long endMillis = parseTimestamp(matcher.group(2));
            final StringBuilder text = new StringBuilder();
            for (i++; i < lines.length && !lines[i].isBlank(); i++) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(lines[i]);
            }
            addCue(cues, startMillis, endMillis, text.toString());
        }
        return new SubtitleDocument(cues);
    }

    @NonNull
    private static SubtitleDocument parseTtml(@NonNull final String subtitleText)
            throws IOException {
        final Document document = Jsoup.parse(subtitleText, "", Parser.xmlParser());
        final Elements paragraphs = document.getElementsByTag("p");
        final List<CueLine> cues = new ArrayList<>(paragraphs.size());
        for (final Element paragraph : paragraphs) {
            final long startMillis;
            final long endMillis;
            if (paragraph.hasAttr("begin")) {
                startMillis = parseTimestamp(paragraph.attr("begin"));
                if (paragraph.hasAttr("end")) {
                    endMillis = parseTimestamp(paragraph.attr("end"));
                } else if (paragraph.hasAttr("dur")) {
                    endMillis = startMillis + parseTimestamp(paragraph.attr("dur"));
                } else {
                    continue;
                }
            } else if (paragraph.hasAttr("t") && paragraph.hasAttr("d")) {
                // YouTube auto-generated TTML uses compact millisecond timing attributes.
                startMillis = parseMilliseconds(paragraph.attr("t"));
                endMillis = startMillis + parseMilliseconds(paragraph.attr("d"));
            } else {
                continue;
            }
            addCue(cues, startMillis, endMillis, paragraph.text());
        }

        if (cues.isEmpty()) {
            addTranscriptCues(document, cues);
        }
        return new SubtitleDocument(cues);
    }

    private static void addTranscriptCues(@NonNull final Document document,
                                          @NonNull final List<CueLine> cues)
            throws IOException {
        for (final Element transcriptLine : document.getElementsByTag("text")) {
            if (!transcriptLine.hasAttr("start")) {
                continue;
            }

            final long startMillis = parseSeconds(transcriptLine.attr("start"));
            final long endMillis;
            if (transcriptLine.hasAttr("dur")) {
                endMillis = startMillis + parseSeconds(transcriptLine.attr("dur"));
            } else if (transcriptLine.hasAttr("duration")) {
                endMillis = startMillis + parseSeconds(transcriptLine.attr("duration"));
            } else {
                continue;
            }
            addCue(cues, startMillis, endMillis, transcriptLine.text());
        }
    }

    private static void addCue(@NonNull final List<CueLine> cues,
                               final long startMillis,
                               final long endMillis,
                               @NonNull final String rawText) {
        final String text = cleanText(rawText);
        if (!text.isBlank() && endMillis > startMillis) {
            cues.add(new CueLine(startMillis, endMillis, text));
        }
    }

    @NonNull
    private static String cleanText(@NonNull final String rawText) {
        final String withoutTags = INLINE_TAG.matcher(rawText).replaceAll("");
        final StringBuilder result = new StringBuilder();
        for (final String line : withoutTags.split("\\R", -1)) {
            final String cleanedLine = WHITESPACE.matcher(line).replaceAll(" ").trim();
            if (!cleanedLine.isEmpty()) {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(unescapeBasicEntities(cleanedLine));
            }
        }
        return result.toString();
    }

    private static long parseTimestamp(@NonNull final String timestamp) throws IOException {
        final String trimmed = timestamp.trim();
        try {
            if (trimmed.endsWith("ms")) {
                return Math.round(Double.parseDouble(trimmed.substring(0, trimmed.length() - 2)));
            } else if (trimmed.endsWith("s")) {
                return Math.round(Double.parseDouble(trimmed.substring(0, trimmed.length() - 1))
                        * 1000);
            }

            final String[] parts = trimmed.replace(',', '.').split(":");
            double seconds = 0;
            for (final String part : parts) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return Math.round(seconds * 1000);
        } catch (final NumberFormatException e) {
            throw new IOException("Unsupported subtitle timestamp: " + timestamp, e);
        }
    }

    private static long parseMilliseconds(@NonNull final String timestamp) throws IOException {
        try {
            return Math.round(Double.parseDouble(timestamp.trim()));
        } catch (final NumberFormatException e) {
            throw new IOException("Unsupported millisecond subtitle timestamp: " + timestamp, e);
        }
    }

    private static long parseSeconds(@NonNull final String timestamp) throws IOException {
        try {
            return Math.round(Double.parseDouble(timestamp.trim()) * 1000);
        } catch (final NumberFormatException e) {
            throw new IOException("Unsupported second subtitle timestamp: " + timestamp, e);
        }
    }

    @NonNull
    private static String formatTimestamp(final long millis) {
        final long hours = millis / 3_600_000;
        final long minutes = millis / 60_000 % 60;
        final long seconds = millis / 1000 % 60;
        final long milliseconds = millis % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d",
                hours, minutes, seconds, milliseconds);
    }

    @NonNull
    private static String escapeWebVtt(@NonNull final String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    @NonNull
    private static String unescapeBasicEntities(@NonNull final String text) {
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static final class CueLine {
        private final long startMillis;
        private final long endMillis;
        @NonNull
        private final String text;

        private CueLine(final long startMillis, final long endMillis,
                        @NonNull final String text) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.text = text;
        }
    }
}
