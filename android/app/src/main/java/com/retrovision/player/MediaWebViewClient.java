package com.retrovision.player;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Serves files the user linked through the folder picker at
 * https://localhost/_rv_media/&lt;name&gt;?u=&lt;content uri&gt;
 *
 * How WebView treats intercepted responses (Chromium's InputStreamReader):
 * it reads the Range header itself, uses InputStream.available() as the full
 * size, skip()s to the range start and caps the length. So for normal files we
 * hand back the whole file from byte 0 and let WebView cut the range out.
 * Seeking ourselves as well made every jump land in the wrong place, and files
 * with their index at the end never loaded.
 *
 * WebView's sizes are 32-bit, so files over 2 GB take a different route: we
 * position the stream ourselves and make WebView's own size check and skip
 * no-ops (see LargeRangeStream).
 */
public class MediaWebViewClient extends BridgeWebViewClient {

    static final String MEDIA_PATH = "/_rv_media/";
    private static final String TAG = "RetroVisionMedia";

    private final Bridge bridge;

    public MediaWebViewClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        String path = url.getPath();
        if (path != null && path.startsWith(MEDIA_PATH) && bridge.getHost().equals(url.getHost())) {
            String target = url.getQueryParameter("u");
            if (target != null && target.startsWith("content://")) {
                return serve(Uri.parse(target), request.getRequestHeaders());
            }
            return status(400, "Bad Request");
        }
        return super.shouldInterceptRequest(view, request);
    }

    private WebResourceResponse serve(Uri uri, Map<String, String> requestHeaders) {
        ContentResolver resolver = bridge.getContext().getContentResolver();
        ParcelFileDescriptor pfd;
        try {
            pfd = resolver.openFileDescriptor(uri, "r");
        } catch (SecurityException | IOException e) {
            Log.w(TAG, "Cannot open " + uri + ": " + e.getMessage());
            return status(404, "Not Found");
        }
        if (pfd == null) {
            return status(404, "Not Found");
        }

        long total = pfd.getStatSize();
        String mime = mimeTypeFor(resolver, uri);
        String range = header(requestHeaders, "Range");

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Cache-Control", "no-store");

        FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        try {
            if (range == null || total < 0) {
                Log.i(TAG, "200 " + mime + " size=" + total);
                return new WebResourceResponse(mime, null, 200, "OK", headers, in);
            }

            long[] span = parseRange(range, total);
            if (span == null) {
                in.close();
                Map<String, String> h = new HashMap<>();
                h.put("Content-Range", "bytes */" + total);
                return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", h, new ByteArrayInputStream(new byte[0]));
            }
            long start = span[0];
            long end = span[1];
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);

            if (total <= Integer.MAX_VALUE) {
                // Whole file from byte 0: WebView skips to `start` and stops after `end`.
                Log.i(TAG, "206 bytes " + start + "-" + end + "/" + total);
                return new WebResourceResponse(mime, null, 206, "Partial Content", headers, in);
            }

            seek(in, start);
            Log.i(TAG, "206 large bytes " + start + "-" + end + "/" + total);
            return new WebResourceResponse(mime, null, 206, "Partial Content", headers, new LargeRangeStream(in, end - start + 1));
        } catch (IOException e) {
            Log.w(TAG, "Failed to stream " + uri + ": " + e.getMessage());
            try {
                in.close();
            } catch (IOException ignored) {}
            return status(500, "Internal Server Error");
        }
    }

    /** Parses "bytes=start-end", "bytes=start-" or "bytes=-suffix". Returns null when unsatisfiable. */
    static long[] parseRange(String header, long total) {
        String value = header.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("bytes=")) return null;
        String spec = value.substring(6).split(",")[0].trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (a.isEmpty()) {
                long suffix = Long.parseLong(b);
                if (suffix <= 0) return null;
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? total - 1 : Math.min(Long.parseLong(b), total - 1);
            }
            if (start >= total || start > end) return null;
            return new long[] { start, end };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void seek(FileInputStream in, long offset) throws IOException {
        if (offset <= 0) return;
        try {
            in.getChannel().position(offset);
        } catch (IOException | RuntimeException e) {
            // Not seekable (for example a pipe from a cloud provider): skip forward instead.
            long remaining = offset;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    if (in.read() < 0) throw new IOException("Unexpected end of stream while seeking");
                    skipped = 1;
                }
                remaining -= skipped;
            }
        }
    }

    private static String mimeTypeFor(ContentResolver resolver, Uri uri) {
        String type = null;
        try {
            type = resolver.getType(uri);
        } catch (RuntimeException ignored) {}
        if (type == null || type.equals("application/octet-stream")) {
            String name = uri.getLastPathSegment();
            if (name != null) {
                int dot = name.lastIndexOf('.');
                if (dot >= 0) {
                    String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                    String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    if (guess != null) type = guess;
                    else if (ext.equals("mkv")) type = "video/x-matroska";
                }
            }
        }
        return type != null ? type : "application/octet-stream";
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static WebResourceResponse status(int code, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", code, reason, new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    /**
     * For files over 2 GB: already positioned at the range start and limited to the
     * range length. Reports 0 available until the first read so WebView skips its
     * 32-bit size check, and treats WebView's skip() as already done.
     */
    static final class LargeRangeStream extends InputStream {

        private final InputStream in;
        private long remaining;
        private boolean started;

        LargeRangeStream(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            started = true;
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            started = true;
            if (remaining <= 0) return -1;
            int n = in.read(buffer, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public long skip(long n) {
            // The stream is already at the range start; report WebView's skip as done.
            return Math.min(n, Integer.MAX_VALUE);
        }

        @Override
        public int available() throws IOException {
            if (!started) return 0;
            return (int) Math.min(Math.min(in.available(), remaining), Integer.MAX_VALUE);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
