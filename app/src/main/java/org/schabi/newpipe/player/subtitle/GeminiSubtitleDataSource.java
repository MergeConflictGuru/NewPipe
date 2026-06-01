/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.subtitle;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;

import com.google.android.exoplayer2.upstream.ByteArrayDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Lazily translates a selected subtitle track and serves it to ExoPlayer as WebVTT.
 */
public final class GeminiSubtitleDataSource implements DataSource {
    private static final String TAG = GeminiSubtitleDataSource.class.getSimpleName();
    private static final String URI_SCHEME = "newpipe-gemini-subtitle";
    private static final String URI_HOST = "translate";
    private static final String MODEL = "gemini-3.1-flash-lite";
    private static final String CACHE_VERSION = "v17";
    private static final String GENERATE_CONTENT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/"
                    + MODEL + ":generateContent";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_BATCH_LINES = 100;
    private static final int MAX_BATCH_CHARACTERS = 12_000;
    private static final long MAX_BATCH_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(3);
    private static final int MAX_ALIGNMENT_BATCH_LINES = 10;
    private static final int MAX_LOGCAT_CHUNK_LENGTH = 3_000;
    private static final int MAX_REQUEST_ATTEMPTS = 3;
    private static final long MIN_REQUEST_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final long RATE_LIMIT_RETRY_MILLIS = TimeUnit.SECONDS.toMillis(20);
    private static final Object GEMINI_RATE_LIMIT_LOCK = new Object();
    private static long nextGeminiRequestAtMillis;
    private static final ExecutorService TRANSLATION_EXECUTOR =
            Executors.newFixedThreadPool(2);
    private static final ConcurrentMap<String, TranslationSession> SESSIONS =
            new ConcurrentHashMap<>();

    @NonNull
    private final Context context;
    @NonNull
    private final OkHttpClient client;
    @NonNull
    private final List<TransferListener> transferListeners = new ArrayList<>();
    @Nullable
    private ByteArrayDataSource delegate;

    private GeminiSubtitleDataSource(@NonNull final Context context) {
        this.context = context.getApplicationContext();
        this.client = DownloaderImpl.getInstance().getClient().newBuilder()
                .callTimeout(90, TimeUnit.SECONDS)
                .build();
    }

    @NonNull
    public static Uri buildUri(@NonNull final String sourceUrl,
                               @NonNull final String stableCacheKey,
                               @NonNull final MediaFormat mediaFormat,
                               @NonNull final String sourceLanguage,
                               @NonNull final String targetLanguage,
                               final boolean showOriginal) {
        return new Uri.Builder()
                .scheme(URI_SCHEME)
                .authority(URI_HOST)
                .appendQueryParameter("source", sourceUrl)
                .appendQueryParameter("cacheKey", stableCacheKey)
                .appendQueryParameter("format", mediaFormat.name())
                .appendQueryParameter("sourceLanguage", sourceLanguage)
                .appendQueryParameter("targetLanguage", targetLanguage)
                .appendQueryParameter("showOriginal", Boolean.toString(showOriginal))
                .build();
    }

    public static boolean isSupported(@NonNull final MediaFormat mediaFormat) {
        return SubtitleDocument.isSupported(mediaFormat);
    }

    @Override
    public void addTransferListener(@NonNull final TransferListener transferListener) {
        transferListeners.add(transferListener);
        if (delegate != null) {
            delegate.addTransferListener(transferListener);
        }
    }

    @Override
    public long open(@NonNull final DataSpec dataSpec) throws IOException {
        final byte[] subtitleBytes = loadOrTranslate(dataSpec.uri);
        delegate = new ByteArrayDataSource(subtitleBytes);
        for (final TransferListener transferListener : transferListeners) {
            delegate.addTransferListener(transferListener);
        }
        return delegate.open(dataSpec);
    }

    @Override
    public int read(@NonNull final byte[] buffer, final int offset, final int length)
            throws IOException {
        if (delegate == null) {
            throw new IOException("Subtitle data source has not been opened");
        }
        return delegate.read(buffer, offset, length);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return delegate == null ? null : delegate.getUri();
    }

    @Override
    public void close() {
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
    }

    @NonNull
    private byte[] loadOrTranslate(@NonNull final Uri uri) throws IOException {
        if (!URI_SCHEME.equals(uri.getScheme())) {
            throw new IOException("Unsupported subtitle URI: " + uri.getScheme());
        }

        final String sourceUrl = requireQueryParameter(uri, "source");
        final String stableCacheKey = requireQueryParameter(uri, "cacheKey");
        final MediaFormat mediaFormat = MediaFormat.valueOf(
                requireQueryParameter(uri, "format"));
        final String sourceLanguage = requireQueryParameter(uri, "sourceLanguage");
        final String targetLanguage = requireQueryParameter(uri, "targetLanguage");
        final boolean showOriginal = Boolean.parseBoolean(
                requireQueryParameter(uri, "showOriginal"));
        Log.d(TAG, "Fetching subtitle source: format=" + mediaFormat);
        final String sourceText = fetchText(sourceUrl);
        Log.d(TAG, "Parsing subtitle source: format=" + mediaFormat);
        final SubtitleDocument subtitleDocument;
        try {
            subtitleDocument = SubtitleDocument.parse(sourceText, mediaFormat);
        } catch (final IOException e) {
            Log.w(TAG, "Could not parse subtitle source: format=" + mediaFormat, e);
            throw e;
        }
        Log.d(TAG, "Parsed subtitle source: cues=" + subtitleDocument.getCueCount());
        final String promptVersion = sha256(
                readPromptTemplate(R.raw.gemini_translation_prompt) + "\n"
                        + readPromptTemplate(R.raw.gemini_alignment_prompt));
        final String sessionId = sha256(CACHE_VERSION + ":" + promptVersion + ":"
                + stableCacheKey + ":" + mediaFormat + ":" + sourceLanguage + ":"
                + targetLanguage + ":" + showOriginal);
        final File cacheFile = new File(getCacheDirectory(), sessionId + ".json");
        final TranslationSession newSession = new TranslationSession(
                sessionId, cacheFile, subtitleDocument, sourceLanguage, targetLanguage,
                showOriginal);
        final TranslationSession session = SESSIONS.compute(sessionId,
                (key, existingSession) -> existingSession == null
                        || existingSession.getCueCount() != subtitleDocument.getCueCount()
                        ? newSession : existingSession);
        session.start();
        return subtitleDocument.toProgressiveWebVtt(sessionId);
    }

    @NonNull
    private List<TranslationLine> translateBatch(@NonNull final List<String> lines,
                                                 @NonNull final String sourceLanguage,
                                                 @NonNull final String targetLanguage,
                                                 @NonNull final String apiKey) throws IOException {
        final JsonArray translatedLines = requestStringArray(
                buildPrompt(lines, sourceLanguage, targetLanguage), lines.size(), apiKey,
                "translation");
        final List<TranslationLine> translations = createEmptyTranslationList(lines.size());
        for (int i = 0; i < translatedLines.size(); i++) {
            final TaggedText translatedLine = parseTaggedText(
                    translatedLines.getString(i), "translated subtitle");
            final int cueIndex = translatedLine.cueIndex;
            final String translation = translatedLine.text;
            if (cueIndex < 0 || cueIndex >= lines.size() || translation.isBlank()
                    || translations.get(cueIndex) != null) {
                throw new IOException("Gemini API returned an invalid translated subtitle ID");
            }
            translations.set(cueIndex, new TranslationLine(translation, List.of(), false));
        }
        requireCompleteBatch(translations, "translated subtitle");
        return translations;
    }

    @NonNull
    private JsonArray requestStringArray(@NonNull final String prompt, final int lineCount,
                                         @NonNull final String apiKey,
                                         @NonNull final String requestType) throws IOException {
        final JsonObject itemSchema = new JsonObject();
        itemSchema.put("type", "string");
        return requestJsonArray(prompt, lineCount, apiKey, requestType, itemSchema);
    }

    @NonNull
    private JsonArray requestAlignmentArray(@NonNull final String prompt, final int lineCount,
                                            @NonNull final String apiKey) throws IOException {
        final JsonObject stringSchema = new JsonObject();
        stringSchema.put("type", "string");

        final JsonObject pairSchema = new JsonObject();
        pairSchema.put("type", "array");
        pairSchema.put("minItems", 2);
        pairSchema.put("maxItems", 2);
        pairSchema.put("items", stringSchema);

        final JsonObject pairsSchema = new JsonObject();
        pairsSchema.put("type", "array");
        pairsSchema.put("items", pairSchema);

        final JsonObject idSchema = new JsonObject();
        idSchema.put("type", "integer");

        final JsonObject properties = new JsonObject();
        properties.put("id", idSchema);
        properties.put("pairs", pairsSchema);

        final JsonArray required = new JsonArray();
        required.add("id");
        required.add("pairs");

        final JsonObject itemSchema = new JsonObject();
        itemSchema.put("type", "object");
        itemSchema.put("properties", properties);
        itemSchema.put("required", required);
        return requestJsonArray(prompt, lineCount, apiKey, "alignment", itemSchema);
    }

    @NonNull
    private JsonArray requestJsonArray(@NonNull final String prompt, final int lineCount,
                                       @NonNull final String apiKey,
                                       @NonNull final String requestType,
                                       @NonNull final JsonObject itemSchema) throws IOException {
        final JsonObject part = new JsonObject();
        part.put("text", prompt);
        final JsonArray parts = new JsonArray();
        parts.add(part);
        final JsonObject content = new JsonObject();
        content.put("parts", parts);
        final JsonArray contents = new JsonArray();
        contents.add(content);

        final JsonObject responseSchema = new JsonObject();
        responseSchema.put("type", "array");
        responseSchema.put("minItems", lineCount);
        responseSchema.put("maxItems", lineCount);
        responseSchema.put("items", itemSchema);
        final JsonObject generationConfig = new JsonObject();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseJsonSchema", responseSchema);
        generationConfig.put("temperature", 0);

        final JsonObject requestJson = new JsonObject();
        requestJson.put("contents", contents);
        requestJson.put("generationConfig", generationConfig);
        final Request request = new Request.Builder()
                .url(GENERATE_CONTENT_URL)
                .header("x-goog-api-key", apiKey)
                .post(RequestBody.create(JsonWriter.string(requestJson), JSON))
                .build();

        for (int attempt = 1; attempt <= MAX_REQUEST_ATTEMPTS; attempt++) {
            waitForGeminiRequestSlot();
            Log.d(TAG, "Sending Gemini subtitle " + requestType + " batch: lines=" + lineCount);
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    final ResponseBody responseBody = response.body();
                    final String errorBody = responseBody == null ? "" : responseBody.string();
                    final String errorMessage = errorBody.isEmpty()
                            ? "" : ": " + summarizeErrorBody(errorBody);
                    if (response.code() == 429 && attempt < MAX_REQUEST_ATTEMPTS
                            && isRetryableGeminiRateLimit(errorBody)) {
                        Log.w(TAG, "Gemini rate limit reached; retrying after delay");
                        sleepForRateLimit(RATE_LIMIT_RETRY_MILLIS);
                        continue;
                    }
                    throw new IOException("Gemini API request failed with HTTP " + response.code()
                            + errorMessage);
                }
                final ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    throw new IOException("Gemini API returned an empty response");
                }
                final String responseText = responseBody.string();
                final String generatedText = extractGeneratedText(responseText);
                logDebugResponse(generatedText);
                final JsonArray result = parseStringArray(generatedText);
                if (result.size() != lineCount) {
                    throw new IOException("Gemini API returned an unexpected number of lines");
                }
                return result;
            }
        }
        throw new IOException("Gemini API request retries exhausted");
    }

    private static void waitForGeminiRequestSlot() throws IOException {
        synchronized (GEMINI_RATE_LIMIT_LOCK) {
            final long waitMillis = nextGeminiRequestAtMillis - System.currentTimeMillis();
            if (waitMillis > 0) {
                sleepForRateLimit(waitMillis);
            }
            nextGeminiRequestAtMillis = System.currentTimeMillis() + MIN_REQUEST_INTERVAL_MILLIS;
        }
    }

    private static void sleepForRateLimit(final long durationMillis) throws IOException {
        try {
            Thread.sleep(durationMillis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Gemini API quota", e);
        }
    }

    static boolean isRetryableGeminiRateLimit(@NonNull final String errorBody) {
        return !errorBody.contains("GenerateRequestsPerDay");
    }

    @NonNull
    private String buildPrompt(@NonNull final List<String> lines,
                               @NonNull final String sourceLanguage,
                               @NonNull final String targetLanguage) throws IOException {
        final JsonArray inputs = new JsonArray(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            final JsonObject input = new JsonObject();
            input.put("id", i);
            input.put("text", lines.get(i));
            inputs.add(input);
        }
        String prompt = readPromptTemplate(R.raw.gemini_translation_prompt);
        prompt = replacePromptMarker(prompt, "{{SOURCE_LANGUAGE}}", sourceLanguage);
        prompt = replacePromptMarker(prompt, "{{TARGET_LANGUAGE}}", targetLanguage);
        return replacePromptMarker(prompt, "{{INPUTS}}", JsonWriter.string(inputs));
    }

    @NonNull
    private List<TranslationLine> alignBatch(@NonNull final List<String> sourceLines,
                                             @NonNull final List<TranslationLine> translatedLines,
                                             @NonNull final String apiKey) throws IOException {
        final List<TranslationLine> alignedLines = createEmptyTranslationList(sourceLines.size());
        for (int batchStart = 0; batchStart < sourceLines.size();
             batchStart += MAX_ALIGNMENT_BATCH_LINES) {
            final int batchEnd = Math.min(
                    sourceLines.size(), batchStart + MAX_ALIGNMENT_BATCH_LINES);
            final JsonArray inputs = new JsonArray(batchEnd - batchStart);
            for (int i = batchStart; i < batchEnd; i++) {
                final JsonObject input = new JsonObject();
                input.put("id", i - batchStart);
                input.put("source", sourceLines.get(i));
                input.put("translation", translatedLines.get(i).translation);
                inputs.add(input);
            }
            final JsonArray alignmentObjects = requestAlignmentArray(
                    buildAlignmentPrompt(inputs), inputs.size(), apiKey);
            for (int i = 0; i < alignmentObjects.size(); i++) {
                final JsonObject alignmentObject = alignmentObjects.getObject(i);
                final int cueIndex = alignmentObject.getInt("id", -1);
                final JsonArray pairs = alignmentObject.getArray("pairs");
                final int lineIndex = batchStart + cueIndex;
                if (cueIndex < 0 || lineIndex >= batchEnd || pairs == null
                        || alignedLines.get(lineIndex) != null) {
                    throw new IOException("Gemini API returned an invalid subtitle alignment ID");
                }
                final List<Alignment> alignments = parseAlignments(
                        JsonWriter.string(pairs), sourceLines.get(lineIndex),
                        translatedLines.get(lineIndex).translation);
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Accepted Gemini subtitle alignments: cue=" + lineIndex
                            + " returned=" + pairs.size() + " accepted=" + alignments.size());
                }
                alignedLines.set(lineIndex, new TranslationLine(
                        translatedLines.get(lineIndex).translation, alignments, true));
            }
        }
        requireCompleteBatch(alignedLines, "subtitle alignment");
        return alignedLines;
    }

    @NonNull
    private String buildAlignmentPrompt(@NonNull final JsonArray inputs) throws IOException {
        return replacePromptMarker(readPromptTemplate(R.raw.gemini_alignment_prompt),
                "{{INPUTS}}", JsonWriter.string(inputs));
    }

    @NonNull
    private String readPromptTemplate(@RawRes final int promptResource) throws IOException {
        try (InputStream inputStream = context.getResources().openRawResource(promptResource)) {
            return new String(readAllBytes(inputStream), StandardCharsets.UTF_8);
        }
    }

    @NonNull
    private static String replacePromptMarker(@NonNull final String prompt,
                                              @NonNull final String marker,
                                              @NonNull final String value) throws IOException {
        if (!prompt.contains(marker)) {
            throw new IOException("Gemini prompt template is missing marker " + marker);
        }
        return prompt.replace(marker, value);
    }

    @NonNull
    private static TaggedText parseTaggedText(@NonNull final String encodedText,
                                              @NonNull final String type) throws IOException {
        final int markerEnd = encodedText.indexOf('>');
        if (!encodedText.startsWith("<") || markerEnd <= 1) {
            throw new IOException("Gemini API returned a malformed " + type + " ID");
        }
        try {
            return new TaggedText(Integer.parseInt(encodedText.substring(1, markerEnd)),
                    encodedText.substring(markerEnd + 1));
        } catch (final NumberFormatException e) {
            throw new IOException("Gemini API returned a malformed " + type + " ID", e);
        }
    }

    @NonNull
    private static List<TranslationLine> createEmptyTranslationList(final int size) {
        final List<TranslationLine> translations = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            translations.add(null);
        }
        return translations;
    }

    private static void requireCompleteBatch(@NonNull final List<TranslationLine> lines,
                                             @NonNull final String type) throws IOException {
        for (final TranslationLine line : lines) {
            if (line == null) {
                throw new IOException("Gemini API omitted a " + type + " ID");
            }
        }
    }

    @NonNull
    static List<Alignment> parseAlignments(@NonNull final String encodedAlignments,
                                           @NonNull final String sourceLine,
                                           @NonNull final String translation) {
        final List<Alignment> alignments = new ArrayList<>();
        final List<Range> usedSourceRanges = new ArrayList<>();
        final List<Range> usedTargetRanges = new ArrayList<>();
        try {
            final JsonArray tuples = JsonParser.array().from(encodedAlignments);
            for (int i = 0; i < tuples.size(); i++) {
                final JsonArray tuple = tuples.getArray(i);
                final String source = tuple.getString(0, "");
                final String target = tuple.getString(1, "");
                final Occurrence sourceOccurrence = findFirstUnusedOccurrence(
                        sourceLine, source, false, usedSourceRanges);
                final Occurrence targetOccurrence = findFirstUnusedOccurrence(
                        translation, target, true, usedTargetRanges);
                if (!source.isBlank() && !target.isBlank() && sourceOccurrence != null
                        && targetOccurrence != null) {
                    alignments.add(new Alignment(
                            source, target, sourceOccurrence.index, targetOccurrence.index));
                    usedSourceRanges.add(new Range(
                            sourceOccurrence.start, sourceOccurrence.start + source.length()));
                    usedTargetRanges.add(new Range(
                            targetOccurrence.start, targetOccurrence.start + target.length()));
                }
            }
        } catch (final JsonParserException | IndexOutOfBoundsException
                       | NullPointerException e) {
            Log.w(TAG, "Ignoring malformed Gemini subtitle alignments");
        }
        return alignments;
    }

    @Nullable
    private static Occurrence findFirstUnusedOccurrence(
            @NonNull final String text,
            @NonNull final String substring,
            final boolean ignoreCase,
            @NonNull final List<Range> usedRanges) {
        if (substring.isBlank()) {
            return null;
        }
        for (int occurrenceIndex = 0; ; occurrenceIndex++) {
            final int start = findOccurrence(text, substring, occurrenceIndex, ignoreCase);
            if (start < 0) {
                return null;
            }
            if (!overlapsAny(usedRanges, start, start + substring.length())) {
                return new Occurrence(start, occurrenceIndex);
            }
        }
    }

    private static boolean overlapsAny(@NonNull final List<Range> ranges,
                                       final int start,
                                       final int end) {
        for (final Range range : ranges) {
            if (start < range.end && end > range.start) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static String extractGeneratedText(@NonNull final String responseBody)
            throws IOException {
        try {
            final JsonObject response = JsonParser.object().from(responseBody);
            return response.getArray("candidates")
                    .getObject(0)
                    .getObject("content")
                    .getArray("parts")
                    .getObject(0)
                    .getString("text");
        } catch (final JsonParserException | IndexOutOfBoundsException
                       | NullPointerException e) {
            throw new IOException("Could not parse Gemini API response", e);
        }
    }

    @NonNull
    private static JsonArray parseStringArray(@NonNull final String generatedText)
            throws IOException {
        try {
            return JsonParser.array().from(generatedText);
        } catch (final JsonParserException e) {
            throw new IOException("Could not parse Gemini generated text", e);
        }
    }

    @NonNull
    private static TranslationLine parseTranslationLine(@NonNull final JsonObject jsonLine,
                                                        @NonNull final String sourceLine)
            throws IOException {
        final String translation = jsonLine.getString("translation", "");
        if (translation.isBlank()) {
            throw new IOException("Gemini API returned an empty translation");
        }

        final JsonArray sources = jsonLine.getArray("alignmentSources", new JsonArray());
        final JsonArray targets = jsonLine.getArray("alignmentTargets", new JsonArray());
        final JsonArray sourceOccurrences =
                jsonLine.getArray("alignmentSourceOccurrences", new JsonArray());
        final JsonArray targetOccurrences =
                jsonLine.getArray("alignmentTargetOccurrences", new JsonArray());
        final int alignmentCount = Math.min(
                Math.min(sources.size(), targets.size()),
                Math.min(sourceOccurrences.size(), targetOccurrences.size()));
        final List<Alignment> alignments = new ArrayList<>(alignmentCount);
        for (int i = 0; i < alignmentCount; i++) {
            final String source = sources.getString(i, "");
            final String target = targets.getString(i, "");
            final int sourceOccurrence = sourceOccurrences.getInt(i, -1);
            final int targetOccurrence = targetOccurrences.getInt(i, -1);
            if (!source.isBlank() && !target.isBlank() && sourceOccurrence >= 0
                    && targetOccurrence >= 0
                    && findOccurrence(sourceLine, source, sourceOccurrence, false) >= 0
                    && findOccurrence(translation, target, targetOccurrence, true) >= 0) {
                alignments.add(new Alignment(
                        source, target, sourceOccurrence, targetOccurrence));
            }
        }
        return new TranslationLine(
                translation, alignments, jsonLine.getInt("alignmentCompleted", 0) == 1);
    }

    static int findOccurrence(@NonNull final String text, @NonNull final String substring,
                              final int occurrence, final boolean ignoreCase) {
        final String searchableText = ignoreCase
                ? text.toLowerCase(java.util.Locale.ROOT) : text;
        final String searchableSubstring = ignoreCase
                ? substring.toLowerCase(java.util.Locale.ROOT) : substring;
        int index = -1;
        for (int i = 0; i <= occurrence; i++) {
            index = searchableText.indexOf(searchableSubstring, index + 1);
            if (index < 0) {
                return -1;
            }
        }
        return index;
    }

    @NonNull
    private String fetchText(@NonNull final String url) throws IOException {
        final Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", DownloaderImpl.USER_AGENT)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Subtitle request failed with HTTP " + response.code());
            }
            final ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Subtitle request returned an empty response");
            }
            return responseBody.string();
        }
    }

    @NonNull
    private File getCacheDirectory() throws IOException {
        final File cacheDir = context.getCacheDir();
        if (cacheDir == null) {
            throw new IOException("Cache directory is unavailable");
        }
        final File cacheDirectory = new File(cacheDir, "gemini-subtitles");
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()
                && !cacheDirectory.isDirectory()) {
            throw new IOException("Could not create Gemini subtitle cache");
        }
        return cacheDirectory;
    }

    private static void writeCache(@NonNull final File cacheFile,
                                   @NonNull final byte[] bytes) throws IOException {
        final File parentDirectory = cacheFile.getParentFile();
        if (parentDirectory == null) {
            throw new IOException("Cache file has no parent directory");
        }
        if (!parentDirectory.isDirectory() && !parentDirectory.mkdirs()
                && !parentDirectory.isDirectory()) {
            throw new IOException("Could not create Gemini subtitle cache");
        }
        final File temporaryFile = new File(parentDirectory, cacheFile.getName() + ".tmp");
        try (FileOutputStream outputStream = new FileOutputStream(temporaryFile)) {
            outputStream.write(bytes);
        }
        if (cacheFile.exists() && !cacheFile.delete()) {
            throw new IOException("Could not replace Gemini subtitle cache");
        }
        if (!temporaryFile.renameTo(cacheFile)) {
            throw new IOException("Could not commit Gemini subtitle cache");
        }
    }

    @NonNull
    private static String requireQueryParameter(@NonNull final Uri uri,
                                                @NonNull final String name) throws IOException {
        final String value = uri.getQueryParameter(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing subtitle URI parameter: " + name);
        }
        return value;
    }

    @NonNull
    private static byte[] readAllBytes(@NonNull final InputStream inputStream) throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        return outputStream.toByteArray();
    }

    @NonNull
    private static String summarizeErrorBody(@NonNull final String responseBody) {
        final String singleLineBody = responseBody.replace('\n', ' ').replace('\r', ' ');
        return singleLineBody.substring(0, Math.min(singleLineBody.length(), 1000));
    }

    private static void logDebugResponse(@NonNull final String responseBody) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        final int chunkCount = Math.max(1,
                (responseBody.length() + MAX_LOGCAT_CHUNK_LENGTH - 1)
                        / MAX_LOGCAT_CHUNK_LENGTH);
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            final int start = chunkIndex * MAX_LOGCAT_CHUNK_LENGTH;
            final int end = Math.min(responseBody.length(), start + MAX_LOGCAT_CHUNK_LENGTH);
            Log.d(TAG, responseBody.substring(start, end));
        }
    }

    @NonNull
    private static String sha256(@NonNull final String value) throws IOException {
        try {
            final byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder result = new StringBuilder(bytes.length * 2);
            for (final byte currentByte : bytes) {
                result.append(String.format("%02x", currentByte & 0xff));
            }
            return result.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    @Nullable
    static TranslationLine getTranslation(@NonNull final String sessionId, final int cueIndex) {
        final TranslationSession session = SESSIONS.get(sessionId);
        return session == null ? null : session.getTranslation(cueIndex);
    }

    static boolean shouldShowOriginal(@NonNull final String sessionId) {
        final TranslationSession session = SESSIONS.get(sessionId);
        return session != null && session.showOriginal;
    }

    static final class Alignment {
        @NonNull
        final String source;
        @NonNull
        final String target;
        final int sourceOccurrence;
        final int targetOccurrence;

        private Alignment(@NonNull final String source, @NonNull final String target,
                          final int sourceOccurrence, final int targetOccurrence) {
            this.source = source;
            this.target = target;
            this.sourceOccurrence = sourceOccurrence;
            this.targetOccurrence = targetOccurrence;
        }
    }

    private static final class Range {
        private final int start;
        private final int end;

        private Range(final int start, final int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class Occurrence {
        private final int start;
        private final int index;

        private Occurrence(final int start, final int index) {
            this.start = start;
            this.index = index;
        }
    }

    static final class TranslationLine {
        @NonNull
        final String translation;
        @NonNull
        final List<Alignment> alignments;
        final boolean alignmentCompleted;

        private TranslationLine(@NonNull final String translation,
                                @NonNull final List<Alignment> alignments,
                                final boolean alignmentCompleted) {
            this.translation = translation;
            this.alignments = alignments;
            this.alignmentCompleted = alignmentCompleted;
        }
    }

    private static final class TaggedText {
        private final int cueIndex;
        @NonNull
        private final String text;

        private TaggedText(final int cueIndex, @NonNull final String text) {
            this.cueIndex = cueIndex;
            this.text = text;
        }
    }

    private final class TranslationSession {
        @NonNull
        private final String sessionId;
        @NonNull
        private final File cacheFile;
        @NonNull
        private final SubtitleDocument subtitleDocument;
        @NonNull
        private final String sourceLanguage;
        @NonNull
        private final String targetLanguage;
        private final boolean showOriginal;
        @NonNull
        private final List<TranslationLine> translations;
        @NonNull
        private final AtomicBoolean translating = new AtomicBoolean();

        private TranslationSession(@NonNull final String sessionId,
                                   @NonNull final File cacheFile,
                                   @NonNull final SubtitleDocument subtitleDocument,
                                   @NonNull final String sourceLanguage,
                                   @NonNull final String targetLanguage,
                                   final boolean showOriginal) {
            this.sessionId = sessionId;
            this.cacheFile = cacheFile;
            this.subtitleDocument = subtitleDocument;
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
            this.showOriginal = showOriginal;
            this.translations = new ArrayList<>(subtitleDocument.getCueCount());
            for (int i = 0; i < subtitleDocument.getCueCount(); i++) {
                translations.add(null);
            }
            loadCache();
        }

        private int getCueCount() {
            return translations.size();
        }

        @Nullable
        private synchronized TranslationLine getTranslation(final int cueIndex) {
            return cueIndex >= 0 && cueIndex < translations.size()
                    ? translations.get(cueIndex) : null;
        }

        private void start() {
            if ((findFirstMissingTranslation() < translations.size()
                    || findFirstMissingAlignment() < translations.size())
                    && translating.compareAndSet(false, true)) {
                TRANSLATION_EXECUTOR.execute(this::translateInBackground);
            }
        }

        private void translateInBackground() {
            try {
                final String apiKey = GeminiSubtitleSettings.getApiKey(context);
                if (apiKey.isBlank()) {
                    throw new IOException("Gemini API key is not configured");
                }

                int batchStart = findFirstMissingTranslation();
                while (batchStart < translations.size()) {
                    final int batchEnd = subtitleDocument.getBatchEndIndex(
                            batchStart, MAX_BATCH_LINES, MAX_BATCH_CHARACTERS,
                            MAX_BATCH_DURATION_MILLIS);
                    final List<String> sourceLines =
                            subtitleDocument.getTexts(batchStart, batchEnd);
                    Log.d(TAG, "Translating Gemini subtitle chunk: cues=" + batchStart + "-"
                            + (batchEnd - 1));
                    final List<TranslationLine> translatedLines = translateBatch(
                            sourceLines, sourceLanguage, targetLanguage, apiKey);
                    synchronized (this) {
                        for (int i = 0; i < translatedLines.size(); i++) {
                            translations.set(batchStart + i, translatedLines.get(i));
                        }
                    }
                    saveCache();
                    GeminiSubtitleRenderer.notifyTranslationsChanged();
                    alignTranslatedPrefix(batchEnd, apiKey);
                    batchStart = findFirstMissingTranslation();
                }
                alignTranslatedPrefix(translations.size(), apiKey);
                Log.d(TAG, "Gemini subtitle background work succeeded");
            } catch (final Exception e) {
                Log.w(TAG, "Could not finish Gemini subtitle background work", e);
            } finally {
                translating.set(false);
            }
        }

        private void alignTranslatedPrefix(final int translatedLimit,
                                           @NonNull final String apiKey) throws IOException {
            int alignmentIndex = findFirstMissingAlignment(translatedLimit);
            while (alignmentIndex < translatedLimit) {
                final int batchEnd = Math.min(
                        translatedLimit, alignmentIndex + MAX_ALIGNMENT_BATCH_LINES);
                final List<TranslationLine> translatedLines = new ArrayList<>();
                synchronized (this) {
                    for (int i = alignmentIndex; i < batchEnd; i++) {
                        final TranslationLine line = translations.get(i);
                        if (line == null || line.alignmentCompleted) {
                            break;
                        }
                        translatedLines.add(line);
                    }
                }
                if (translatedLines.isEmpty()) {
                    return;
                }

                final int actualBatchEnd = alignmentIndex + translatedLines.size();
                final List<String> sourceLines =
                        subtitleDocument.getTexts(alignmentIndex, actualBatchEnd);
                Log.d(TAG, "Aligning Gemini subtitle chunk: cues=" + alignmentIndex + "-"
                        + (actualBatchEnd - 1));
                final List<TranslationLine> alignedLines = alignBatch(
                        sourceLines, translatedLines, apiKey);
                synchronized (this) {
                    for (int i = 0; i < alignedLines.size(); i++) {
                        translations.set(alignmentIndex + i, alignedLines.get(i));
                    }
                }
                saveCache();
                GeminiSubtitleRenderer.notifyTranslationsChanged();
                alignmentIndex = findFirstMissingAlignment(translatedLimit);
            }
        }

        private synchronized int findFirstMissingTranslation() {
            for (int i = 0; i < translations.size(); i++) {
                if (translations.get(i) == null) {
                    return i;
                }
            }
            return translations.size();
        }

        private synchronized int findFirstMissingAlignment() {
            return findFirstMissingAlignment(translations.size());
        }

        private synchronized int findFirstMissingAlignment(final int toIndex) {
            for (int i = 0; i < toIndex; i++) {
                final TranslationLine translation = translations.get(i);
                if (translation != null && !translation.alignmentCompleted) {
                    return i;
                }
            }
            return toIndex;
        }

        private synchronized void loadCache() {
            if (!cacheFile.isFile()) {
                return;
            }
            try (InputStream inputStream = new FileInputStream(cacheFile)) {
                final JsonObject jsonCache = JsonParser.object().from(
                        new String(readAllBytes(inputStream), StandardCharsets.UTF_8));
                final JsonArray jsonLines = jsonCache.getArray("lines");
                if (jsonCache.getInt("cueCount") != translations.size()
                        || jsonLines.size() != translations.size()) {
                    return;
                }

                int loadedCount = 0;
                final List<String> sourceLines = subtitleDocument.getTexts();
                for (int i = 0; i < jsonLines.size(); i++) {
                    if (!jsonLines.isNull(i)) {
                        translations.set(i, parseTranslationLine(
                                jsonLines.getObject(i), sourceLines.get(i)));
                        loadedCount++;
                    }
                }
                Log.d(TAG, "Loaded cached Gemini subtitles: cues=" + loadedCount);
            } catch (final IOException | JsonParserException e) {
                Log.w(TAG, "Could not read Gemini subtitle cache", e);
            }
        }

        private synchronized void saveCache() {
            final JsonObject jsonCache = new JsonObject();
            jsonCache.put("sessionId", sessionId);
            jsonCache.put("cueCount", translations.size());
            final JsonArray jsonLines = new JsonArray(translations.size());
            for (final TranslationLine translation : translations) {
                if (translation == null) {
                    jsonLines.add(null);
                    continue;
                }

                final JsonObject jsonLine = new JsonObject();
                jsonLine.put("translation", translation.translation);
                jsonLine.put("alignmentCompleted", translation.alignmentCompleted ? 1 : 0);
                final JsonArray jsonSources = new JsonArray(translation.alignments.size());
                final JsonArray jsonTargets = new JsonArray(translation.alignments.size());
                final JsonArray jsonSourceOccurrences =
                        new JsonArray(translation.alignments.size());
                final JsonArray jsonTargetOccurrences =
                        new JsonArray(translation.alignments.size());
                for (final Alignment alignment : translation.alignments) {
                    jsonSources.add(alignment.source);
                    jsonTargets.add(alignment.target);
                    jsonSourceOccurrences.add(alignment.sourceOccurrence);
                    jsonTargetOccurrences.add(alignment.targetOccurrence);
                }
                jsonLine.put("alignmentSources", jsonSources);
                jsonLine.put("alignmentTargets", jsonTargets);
                jsonLine.put("alignmentSourceOccurrences", jsonSourceOccurrences);
                jsonLine.put("alignmentTargetOccurrences", jsonTargetOccurrences);
                jsonLines.add(jsonLine);
            }
            jsonCache.put("lines", jsonLines);

            try {
                writeCache(cacheFile,
                        JsonWriter.string(jsonCache).getBytes(StandardCharsets.UTF_8));
            } catch (final IOException e) {
                Log.w(TAG, "Could not cache Gemini subtitles", e);
            }
        }
    }

    public static final class Factory implements DataSource.Factory {
        @NonNull
        private final Context context;

        public Factory(@NonNull final Context context) {
            this.context = context;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new GeminiSubtitleDataSource(context);
        }
    }
}
