package com.retrovision.player;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.view.WindowManager;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Native replacement for the browser's "link a folder" picker (showDirectoryPicker),
 * which Android WebView doesn't have. Files stay where they are: we keep a persisted
 * read grant on the picked folder and stream files from it via MediaWebViewClient.
 */
@CapacitorPlugin(name = "MediaFolder")
public class MediaFolderPlugin extends Plugin {

    private static final Pattern MEDIA_NAME = Pattern.compile(
        "\\.(mp4|m4v|mkv|webm|mov|avi|ogv|mpg|mpeg|ts|mp3|m4a|aac|flac|ogg|oga|wav|opus)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern AUDIO_NAME = Pattern.compile("\\.(mp3|m4a|aac|flac|ogg|oga|wav|opus)$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_DEPTH = 12;

    private final ExecutorService walker = Executors.newSingleThreadExecutor();
    private final ExecutorService probes = Executors.newFixedThreadPool(4);

    /** Opens the system folder picker and returns every media file found inside (recursively). */
    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, "onFolderPicked");
    }

    @ActivityCallback
    private void onFolderPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.reject("Folder picker cancelled", "CANCELLED");
            return;
        }
        final Uri tree = data.getData();
        final ContentResolver resolver = getContext().getContentResolver();
        try {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Still readable for this session; the grant just won't survive a restart.
        }

        walker.execute(() -> {
            try {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                JSArray files = new JSArray();
                walk(resolver, tree, rootId, new ArrayList<>(), files, 0);
                JSObject ret = new JSObject();
                ret.put("rootName", displayName(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId)));
                ret.put("treeUri", tree.toString());
                ret.put("files", files);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Could not read that folder", e);
            }
        });
    }

    private void walk(ContentResolver resolver, Uri tree, String docId, List<String> parts, JSArray out, int depth) {
        if (depth > MAX_DEPTH) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] columns = {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE
        };
        try (Cursor c = resolver.query(children, columns, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                if (id == null || name == null || name.startsWith(".")) continue;

                if (Document.MIME_TYPE_DIR.equals(mime)) {
                    List<String> next = new ArrayList<>(parts);
                    next.add(name);
                    walk(resolver, tree, id, next, out, depth + 1);
                } else if (isMedia(name, mime)) {
                    JSArray folderParts = new JSArray();
                    for (String p : parts) folderParts.put(p);
                    JSObject file = new JSObject();
                    file.put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
                    file.put("name", name);
                    file.put("size", size);
                    file.put("type", mediaType(name, mime));
                    file.put("parts", folderParts);
                    out.put(file);
                }
            }
        }
    }

    /** Reads a file's duration in seconds (0 when it can't be read). */
    @PluginMethod
    public void probe(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null) {
            call.reject("uri is required");
            return;
        }
        probes.execute(() -> {
            double seconds = 0;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(getContext(), Uri.parse(uri));
                String ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (ms != null) seconds = Long.parseLong(ms) / 1000.0;
            } catch (Exception ignored) {
                // Unreadable or unsupported file: report 0, the web side has a fallback.
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {}
            }
            JSObject ret = new JSObject();
            ret.put("duration", seconds);
            call.resolve(ret);
        });
    }

    /** Whether a linked file can still be opened (permission kept, file not moved or deleted). */
    @PluginMethod
    public void check(PluginCall call) {
        String uri = call.getString("uri");
        boolean readable = false;
        if (uri != null) {
            try (ParcelFileDescriptor pfd = getContext().getContentResolver().openFileDescriptor(Uri.parse(uri), "r")) {
                readable = pfd != null;
            } catch (Exception ignored) {}
        }
        JSObject ret = new JSObject();
        ret.put("readable", readable);
        call.resolve(ret);
    }

    /** Keeps the screen on while something is playing. */
    @PluginMethod
    public void setKeepAwake(PluginCall call) {
        boolean enabled = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        getActivity()
            .runOnUiThread(() -> {
                if (enabled) {
                    getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                call.resolve();
            });
    }

    @Override
    protected void handleOnDestroy() {
        walker.shutdownNow();
        probes.shutdownNow();
    }

    private static boolean isMedia(String name, String mime) {
        if (MEDIA_NAME.matcher(name).find()) return true;
        return mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"));
    }

    private static String mediaType(String name, String mime) {
        if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) return mime;
        String lower = name.toLowerCase(Locale.ROOT);
        if (AUDIO_NAME.matcher(lower).find()) return "audio/mpeg";
        return "video/mp4";
    }

    private static String displayName(ContentResolver resolver, Uri doc) {
        try (Cursor c = resolver.query(doc, new String[] { Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "";
    }
}
