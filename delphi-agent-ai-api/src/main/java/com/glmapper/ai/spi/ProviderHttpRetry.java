package com.glmapper.ai.spi;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class ProviderHttpRetry {
    private ProviderHttpRetry() {
    }

    public static HttpResponse<String> sendWithRetry(
            HttpClient client,
            HttpRequest request,
            int maxAttempts,
            long maxRetryDelayMs
    ) throws IOException, InterruptedException {
        int attempts = Math.max(1, maxAttempts);
        long backoffMs = 1_000;
        IOException lastIoException = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (!isRetryableStatus(response.statusCode()) || attempt == attempts) {
                    return response;
                }

                long delayMs = computeDelayMillis(response.headers(), backoffMs);
                if (delayMs > maxRetryDelayMs) {
                    throw new IllegalStateException("Server requested retry delay " + delayMs
                            + "ms above maxRetryDelayMs=" + maxRetryDelayMs + "ms");
                }
                Thread.sleep(Math.max(0, delayMs));
                backoffMs = Math.min(Math.max(backoffMs * 2, 1_000), Math.max(maxRetryDelayMs, 1_000));
            } catch (IOException ioEx) {
                lastIoException = ioEx;
                if (attempt == attempts) {
                    throw ioEx;
                }
                long delayMs = Math.min(backoffMs, Math.max(maxRetryDelayMs, 1_000));
                Thread.sleep(Math.max(0, delayMs));
                backoffMs = Math.min(Math.max(backoffMs * 2, 1_000), Math.max(maxRetryDelayMs, 1_000));
            }
        }

        if (lastIoException != null) {
            throw lastIoException;
        }
        throw new IllegalStateException("Unreachable retry branch");
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode == 408 || (statusCode >= 500 && statusCode <= 599);
    }

    private static long computeDelayMillis(HttpHeaders headers, long fallbackBackoffMs) {
        String retryAfter = headers.firstValue("retry-after").orElse(null);
        if (retryAfter == null || retryAfter.isBlank()) {
            return fallbackBackoffMs;
        }

        try {
            double seconds = Double.parseDouble(retryAfter.trim());
            return Math.max(0, Math.round(seconds * 1000));
        } catch (NumberFormatException ignored) {
        }
        try {
            ZonedDateTime retryAt = ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0, retryAt.toInstant().toEpochMilli() - System.currentTimeMillis());
        } catch (DateTimeParseException ignored) {
        }

        return fallbackBackoffMs;
    }
}
