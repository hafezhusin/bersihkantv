package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import org.json.JSONObject;

import java.io.File;

/**
 * The opt-in read-only companion provider. Only compiled when the companionBridge build flag is on;
 * the default release build has neither this class nor the manifest entry.
 *
 * No network listener and no caller-supplied paths: the only caller is ADB's shell UID or the
 * launcher itself.
 */
public final class CompanionProvider extends ContentProvider {
    private static CompanionStore instance;

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
            });
        }
        return instance;
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
            switch (method) {
                case "capabilities":
                    data = CompanionStore.capabilities(BuildConfig.VERSION_NAME);
                    break;
                case "state":
                    data = store(getContext()).state();
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
