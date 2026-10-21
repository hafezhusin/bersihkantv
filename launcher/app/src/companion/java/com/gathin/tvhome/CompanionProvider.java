package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The opt-in companion protocol v1 provider. Only compiled when the companionBridge build flag is on;
 * the default release build has neither this class nor the manifest entry.
 *
 * No network listener and no caller-supplied paths: the only caller is ADB's shell UID or the
 * launcher itself.
 */
public final class CompanionProvider extends ContentProvider {
    private static CompanionStore instance;
    private static ProviderCredentials providerCredentials;
    private static final ThreadPoolExecutor uploads = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    private static final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();

    static synchronized CompanionStore store(Context context) throws Exception {
        Context app = context.getApplicationContext();
        if (instance == null) {
            instance = new CompanionStore(new File(app.getFilesDir(), "companion"), () -> {
                File dir = Config.dir(app);
                JSONObject theme = dir == null ? null : Config.read(new File(dir, Config.FILE_NAME));
                return new JSONObject()
                        .put("settings", theme == null ? new JSONObject() : theme)
                        .put("preferences", new JSONObject(app.getSharedPreferences(
                                Config.SETTINGS_NAME, Context.MODE_PRIVATE).getAll()));
            }, directory -> {
                FileDescriptor fd = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0);
                try {
                    Os.fsync(fd);
                } finally {
                    Os.close(fd);
                }
            }, (file, mime) -> {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), options);
                CompanionStore.require(options.outWidth > 0 && options.outHeight > 0
                        && options.outWidth <= 16384 && options.outHeight <= 16384
                        && (long) options.outWidth * options.outHeight <= 33177600
                        && mime.equals(options.outMimeType), "invalid_asset");
                // Decode a small copy as well: a truncated file can pass the bounds check.
                BitmapFactory.Options decode = new BitmapFactory.Options();
                decode.inSampleSize = 1;
                while (options.outWidth / decode.inSampleSize > 512
                        || options.outHeight / decode.inSampleSize > 512) {
                    decode.inSampleSize *= 2;
                }
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), decode);
                CompanionStore.require(bitmap != null, "invalid_asset");
                bitmap.recycle();
            });
        }
        return instance;
    }

    /** Theme schedules are advertised only by a bridge build that kept them enabled. */
    private static boolean schedules() {
        return BuildConfig.COMPANION_SCHEDULES;
    }

    /** Provider credentials are advertised only by a bridge build that kept them enabled. */
    private static boolean credentials() {
        return BuildConfig.COMPANION_CREDENTIALS;
    }

    static synchronized ProviderCredentials credentials(Context context) {
        if (providerCredentials == null) {
            Context app = context.getApplicationContext();
            providerCredentials = new ProviderCredentials(
                    new File(app.getFilesDir(), "providers"), new KeystoreVault());
        }
        return providerCredentials;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        Bundle result = new Bundle();
        if (!CompanionAccess.allowed(caller, Process.myUid())) {
            result.putString("reply", CompanionProtocol.encode(denied(arg)));
            return result;
        }
        String requestId = "";
        JSONObject reply;
        try {
            JSONObject request = CompanionProtocol.decode(arg);
            requestId = request.optString("request_id", "");
            CompanionProtocol.request(method, request);
            JSONObject data;
            CompanionStore store = store(getContext());
            switch (method) {
                case "capabilities":
                    data = CompanionStore.capabilities(BuildConfig.VERSION_NAME,
                            schedules(), credentials());
                    break;
                case "state":
                    data = store.state(schedules());
                    if (credentials()) {
                        data.put("providers", credentials(getContext()).status(null));
                    }
                    break;
                case "credentials_status":
                    requireCredentials();
                    data = credentials(getContext()).status(
                            request.has("provider") ? request.getString("provider") : null);
                    break;
                case "credentials_set":
                    requireCredentials();
                    data = credentials(getContext()).set(request.getString("provider"),
                            request.getJSONObject("credential"));
                    break;
                case "credentials_enable":
                    requireCredentials();
                    data = credentials(getContext()).enable(request.getString("provider"),
                            request.getBoolean("enabled"));
                    break;
                case "credentials_revoke":
                    requireCredentials();
                    data = credentials(getContext()).revoke(request.getString("provider"));
                    break;
                case "stage":
                    data = store.stage(request, schedules());
                    break;
                case "validate":
                    data = store.validate(request.getString("session_id"));
                    break;
                case "activate":
                    data = store.activate(request);
                    CompanionBridge.activated();
                    break;
                case "result":
                    data = store.result(request.getString("operation_id"));
                    break;
                case "discard":
                    data = store.discard(request.getString("session_id"));
                    break;
                default:
                    throw new CompanionProtocol.Failure("invalid_request");
            }
            reply = CompanionProtocol.reply(requestId, data, null);
        } catch (CompanionProtocol.Failure failure) {
            reply = error(requestId, failure.code);
        } catch (Exception failure) {
            reply = error(requestId, "internal_error");
        }
        result.putString("reply", CompanionProtocol.encode(reply));
        return result;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        caller();
        try {
            List<String> parts = uri.getPathSegments();
            CompanionStore.require("w".equals(mode) && parts.size() == 5
                    && "v1".equals(parts.get(0)) && "sessions".equals(parts.get(1))
                    && "assets".equals(parts.get(3)), "invalid_request");
            String session = parts.get(2);
            String sha = parts.get(4);
            CompanionStore.require(CompanionProtocol.hex(session, 32)
                    && CompanionProtocol.hex(sha, 64), "invalid_request");
            CompanionStore store = store(getContext());
            store.session(session);
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            try {
                uploads.execute(() -> {
                    ScheduledFuture<?> timeout = timeouts.schedule(() -> {
                        try {
                            pipe[0].closeWithError("Transfer timed out");
                        } catch (IOException ignored) {
                            // already closed
                        }
                    }, 60, TimeUnit.SECONDS);
                    try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                        store.receive(session, sha, in);
                    } catch (Exception failure) {
                        try {
                            pipe[0].closeWithError("Asset rejected");
                        } catch (IOException ignored) {
                            // already closed
                        }
                    } finally {
                        timeout.cancel(false);
                    }
                });
            } catch (RejectedExecutionException busy) {
                pipe[0].close();
                pipe[1].close();
                throw new CompanionProtocol.Failure("busy");
            }
            return pipe[1];
        } catch (CompanionProtocol.Failure failure) {
            throw new FileNotFoundException(failure.code);
        } catch (Exception failure) {
            throw new FileNotFoundException("Companion asset transfer unavailable");
        }
    }

    /** A build without the feature rejects the methods instead of silently accepting a secret. */
    private static void requireCredentials() throws CompanionProtocol.Failure {
        if (!credentials()) throw new CompanionProtocol.Failure("unsupported_feature");
    }

    private static JSONObject denied(String arg) {
        String requestId = "";
        try {
            requestId = CompanionProtocol.decode(arg).optString("request_id", "");
        } catch (Exception ignored) {
            // A denied caller gets no information from a malformed request either.
        }
        return error(requestId, "access_denied");
    }

    private static JSONObject error(String requestId, String code) {
        try {
            return CompanionProtocol.reply(requestId, null, code);
        } catch (Exception impossible) {
            return new JSONObject();
        }
    }

    @Override
    public String getType(Uri uri) {
        caller();
        return "application/json";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        caller();
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        caller();
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        caller();
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        caller();
        throw new UnsupportedOperationException();
    }

    private static void caller() {
        if (!CompanionAccess.allowed(Binder.getCallingUid(), Process.myUid())) {
            throw new SecurityException("Companion access denied");
        }
    }
}
