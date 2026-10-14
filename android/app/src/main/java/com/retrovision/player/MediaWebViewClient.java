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
 * Capacitor's own file route ignores the start of a Range request and caps sizes
 * at 2 GB, which breaks seeking and live-channel tuning. This answers Range
 * requests properly (206 Partial Content) using 64-bit offsets.
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

        try {
            FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);

            if (range == null || total < 0) {
                if (total >= 0) headers.put("Content-Length", String.valueOf(total));
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
            long length = end - start + 1;

            seek(in, start);
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
            headers.put("Content-Length", String.valueOf(length));
            return new WebResourceResponse(mime, null, 206, "Partial Content", headers, new BoundedInputStream(in, length));
        } catch (IOException e) {
            Log.w(TAG, "Failed to stream " + uri + ": " + e.getMessage());
            try {
                pfd.close();
            } catch (IOException ignored) {}
            return status(500, "Internal Server Error");
        }
    }

    /** Parses "bytes=start-end", "bytes=start-" or "bytes=-suffix". Returns null when unsatisfiable. */
    private static long[] parseRange(String header, long total) {
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

    /** Stops reading after {@code limit} bytes and closes the underlying stream when done. */
    private static final class BoundedInputStream extends InputStream {

        private final InputStream in;
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(buffer, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), Math.min(remaining, Integer.MAX_VALUE));
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
