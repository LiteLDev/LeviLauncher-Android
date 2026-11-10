package org.levimc.launcher.core.play;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to the Google Play store API on behalf of the signed-in Google account: registers
 * the launcher as a device, reads Minecraft's listing and resolves the download URLs of a
 * version the account owns. Every method blocks; call them off the main thread.
 */
public final class PlayClient {
    private static final String TAG = "PlayClient";
    private static final String BASE = "https://android.clients.google.com";
    private static final String FDFE = BASE + "/fdfe";
    /** SHA-1 of the certificate Google signs Play services with, which /auth expects callers to name. */
    private static final String GOOGLE_CERT_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788";
    private static final MediaType PROTOBUF = MediaType.get("application/x-protobuf");
    private static final long TOKEN_LIFETIME_FALLBACK_MS = TimeUnit.MINUTES.toMillis(50);

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build();

    /** Minecraft's listing as Play shows it to this account and device. */
    public static final class AppDetails {
        public String packageName;
        public String title;
        public long versionCode;
        public String versionName;
        public long downloadSize;
        public String updatedOn;
        public int offerType = 1;
        public boolean testingProgramAvailable;
        public boolean testingProgramSubscribed;
    }

    /** One file of a delivery: the base APK or one of its splits. */
    public static final class DeliveryFile {
        public String name;
        public String url;
        public long size;
        public String sha1;
        public String sha256;
    }

    public static final class Delivery {
        public long versionCode;
        public final List<DeliveryFile> files = new ArrayList<>();
        public final Map<String, String> cookies = new LinkedHashMap<>();
    }

    private final Context context;
    private final PlayDevice device;
    private PlayAccountStore.Account account;

    public PlayClient(Context context) throws PlayException {
        this.context = context.getApplicationContext();
        this.device = PlayDevice.get(this.context);
        this.account = PlayAccountStore.load(this.context);
        if (account == null) {
            throw new PlayException(PlayException.Kind.UNAUTHORIZED, "Not signed in to a Google account");
        }
    }

    public static boolean isSignedIn(Context context) {
        return PlayAccountStore.load(context) != null;
    }

    public static String signedInEmail(Context context) {
        PlayAccountStore.Account account = PlayAccountStore.load(context);
        return account == null ? null : account.email;
    }

    public static void signOut(Context context) {
        PlayAccountStore.clear(context);
    }

    /**
     * Turns the one-time oauth_token cookie Google's sign-in page leaves into a long-lived
     * AAS token and saves the account. Returns the account's email address.
     */
    public static String signIn(Context context, String email, String oauthToken) throws PlayException {
        PlayDevice device = PlayDevice.get(context);
        FormBody.Builder form = new FormBody.Builder()
                .add("lang", device.locale.toString().replace('_', '-'))
                .add("google_play_services_version", String.valueOf(device.gmsVersion))
                .add("sdk_version", String.valueOf(device.sdk))
                .add("device_country", device.countryCode())
                .add("Email", email == null ? "" : email)
                .add("service", "ac2dm")
                .add("get_accountid", "1")
                .add("ACCESS_TOKEN", "1")
                .add("callerPkg", "com.google.android.gms")
                .add("add_account", "1")
                .add("Token", oauthToken)
                .add("callerSig", GOOGLE_CERT_SHA1)
                .add("droidguard_results", "null");
        Request request = new Request.Builder()
                .url(BASE + "/auth")
                .header("app", "com.google.android.gms")
                .header("User-Agent", device.authUserAgent())
                .post(form.build())
                .build();
        Map<String, String> values = keyValues(execute(request, true));
        String aasToken = values.get("Token");
        if (TextUtils.isEmpty(aasToken)) {
            throw new PlayException(PlayException.Kind.UNAUTHORIZED, "Google did not return an account token");
        }
        String accountEmail = values.get("Email");
        PlayAccountStore.Account account = new PlayAccountStore.Account();
        account.email = TextUtils.isEmpty(accountEmail) ? email : accountEmail;
        account.aasToken = aasToken;
        try {
            PlayAccountStore.save(context, account);
        } catch (Exception e) {
            throw new PlayException(PlayException.Kind.UNKNOWN, "The Google account could not be saved", e);
        }
        return account.email;
    }

    public String email() {
        return account.email;
    }

    public AppDetails details(String packageName) throws PlayException {
        HttpUrl url = HttpUrl.get(FDFE + "/details").newBuilder()
                .addQueryParameter("doc", packageName)
                .build();
        Proto.Message payload = callWithRetry(() -> new Request.Builder().url(url).get());
        try {
            Proto.Message item = payload.path(2, 4);
            if (!item.has(13)) {
                throw new PlayException(PlayException.Kind.NOT_AVAILABLE, "Google Play has no listing for " + packageName);
            }
            Proto.Message app = item.path(13, 1);
            AppDetails details = new AppDetails();
            details.packageName = app.has(14) ? app.string(14) : packageName;
            details.title = item.string(5);
            details.versionCode = app.int64(3);
            details.versionName = app.string(4);
            details.downloadSize = app.int64(9);
            details.updatedOn = app.string(16);
            details.testingProgramAvailable = app.has(35);
            details.testingProgramSubscribed = app.message(35).bool(2);
            List<Proto.Message> offers = item.messages(8);
            if (!offers.isEmpty() && offers.get(0).has(8)) {
                details.offerType = (int) offers.get(0).int64(8);
            }
            return details;
        } catch (RuntimeException e) {
            throw new PlayException(PlayException.Kind.MALFORMED, "Malformed details response", e);
        }
    }

    /**
     * Joins or leaves the app's beta (testing) program for the signed-in account. For
     * Minecraft that is the Preview channel; Play needs a few minutes to start serving it.
     */
    public boolean setTestingProgram(String packageName, boolean subscribe) throws PlayException {
        byte[] body = new Proto.Writer().string(1, packageName).bool(2, subscribe).toByteArray();
        Proto.Message payload = callWithRetry(() -> new Request.Builder()
                .url(FDFE + "/apps/testingProgram")
                .post(RequestBody.create(body, PROTOBUF)));
        Proto.Message details = payload.path(80, 2, 4);
        return subscribe ? details.bool(2) : details.bool(4);
    }

    /** Resolves the download of [versionCode]; throws NOT_PURCHASED if the account does not own it. */
    public Delivery delivery(String packageName, long versionCode, int offerType) throws PlayException {
        String deliveryToken = purchase(packageName, versionCode, offerType);
        HttpUrl.Builder urlBuilder = HttpUrl.get(FDFE + "/delivery").newBuilder()
                .addQueryParameter("ot", String.valueOf(offerType))
                .addQueryParameter("doc", packageName)
                .addQueryParameter("vc", String.valueOf(versionCode));
        if (!TextUtils.isEmpty(deliveryToken)) urlBuilder.addQueryParameter("dtok", deliveryToken);
        HttpUrl url = urlBuilder.build();
        Proto.Message payload = callWithRetry(() -> new Request.Builder().url(url).get());
        try {
            Proto.Message response = payload.message(21);
            // status is declared with default 1 (success), so an absent field means success.
            int status = response.has(1) ? (int) response.int64(1) : 1;
            switch (status) {
                case 1:
                    break;
                case 3:
                    throw new PlayException(PlayException.Kind.NOT_PURCHASED,
                            "This Google account has not purchased Minecraft");
                case 2:
                case 7:
                case 9:
                default:
                    throw new PlayException(PlayException.Kind.NOT_AVAILABLE,
                            "Google Play has no download of version " + versionCode + " for this device (status " + status + ")");
            }
            Proto.Message data = response.message(2);
            Delivery delivery = new Delivery();
            delivery.versionCode = versionCode;
            String baseUrl = data.string(3);
            if (!baseUrl.isEmpty()) {
                DeliveryFile base = new DeliveryFile();
                base.name = "base";
                base.url = baseUrl;
                base.size = data.int64(1);
                base.sha1 = hex(data.string(2));
                base.sha256 = hex(data.string(19));
                delivery.files.add(base);
            }
            for (Proto.Message split : data.messages(15)) {
                String splitUrl = split.string(5);
                if (splitUrl.isEmpty()) continue;
                DeliveryFile file = new DeliveryFile();
                file.name = split.string(1);
                file.url = splitUrl;
                file.size = split.int64(2);
                file.sha1 = hex(split.string(4));
                file.sha256 = hex(split.string(9));
                delivery.files.add(file);
            }
            for (Proto.Message cookie : data.messages(5)) {
                delivery.cookies.put(cookie.string(1), cookie.string(2));
            }
            if (delivery.files.isEmpty()) {
                throw new PlayException(PlayException.Kind.NOT_AVAILABLE, "Google Play returned no files for version " + versionCode);
            }
            return delivery;
        } catch (RuntimeException e) {
            throw new PlayException(PlayException.Kind.MALFORMED, "Malformed delivery response", e);
        }
    }

    /**
     * Asks Play for a delivery token. Play answers paid apps with an error here whether or
     * not the account owns them; /delivery then works without a token, so failures are not fatal.
     */
    private String purchase(String packageName, long versionCode, int offerType) {
        HttpUrl url = HttpUrl.get(FDFE + "/purchase").newBuilder()
                .addQueryParameter("ot", String.valueOf(offerType))
                .addQueryParameter("doc", packageName)
                .addQueryParameter("vc", String.valueOf(versionCode))
                .build();
        try {
            Proto.Message payload = callWithRetry(() -> new Request.Builder().url(url)
                    .post(RequestBody.create(new byte[0], null)));
            return payload.message(4).string(55);
        } catch (PlayException e) {
            if (e.kind == PlayException.Kind.NETWORK) Log.w(TAG, "purchase failed", e);
            return "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private interface RequestFactory {
        Request.Builder create();
    }

    /** Sends a signed /fdfe request; an expired auth token is re-minted and the request retried once. */
    private Proto.Message callWithRetry(RequestFactory factory) throws PlayException {
        ensureSession();
        try {
            return payload(execute(sign(factory.create()).build(), false));
        } catch (PlayException e) {
            if (e.kind != PlayException.Kind.UNAUTHORIZED) throw e;
            account.authToken = null;
            account.authTokenExpiry = 0;
            ensureSession();
            return payload(execute(sign(factory.create()).build(), false));
        }
    }

    private static Proto.Message payload(byte[] body) throws PlayException {
        try {
            return Proto.Message.parse(body).message(1);
        } catch (RuntimeException e) {
            throw new PlayException(PlayException.Kind.MALFORMED, "Malformed response from Google Play", e);
        }
    }

    private Request.Builder sign(Request.Builder builder) {
        builder.header("Authorization", "Bearer " + account.authToken)
                .header("User-Agent", device.storeUserAgent())
                .header("X-DFE-Device-Id", account.gsfId)
                .header("Accept-Language", device.locale.toLanguageTag())
                .header("X-DFE-Client-Id", "am-android-google")
                .header("X-DFE-Network-Type", "4")
                .header("X-DFE-Content-Filters", "")
                .header("X-Limit-Ad-Tracking-Enabled", "false")
                .header("X-Ad-Id", "")
                .header("X-DFE-UserLanguages", device.locale.toString())
                .header("X-DFE-Request-Params", "timeoutMs=4000");
        if (!TextUtils.isEmpty(account.checkinConsistencyToken)) {
            builder.header("X-DFE-Device-Checkin-Consistency-Token", account.checkinConsistencyToken);
        }
        if (!TextUtils.isEmpty(account.deviceConfigToken)) {
            builder.header("X-DFE-Device-Config-Token", account.deviceConfigToken);
        }
        return builder;
    }

    /** Checks the device in, uploads its configuration and mints a Play token, each only when missing. */
    private synchronized void ensureSession() throws PlayException {
        boolean changed = false;
        if (TextUtils.isEmpty(account.gsfId) || !device.fingerprint.equals(account.deviceFingerprint)) {
            checkin();
            account.deviceConfigToken = null;
            changed = true;
        }
        if (TextUtils.isEmpty(account.authToken) || System.currentTimeMillis() >= account.authTokenExpiry) {
            mintAuthToken();
            changed = true;
        }
        if (TextUtils.isEmpty(account.deviceConfigToken)) {
            uploadDeviceConfig();
            changed = true;
        }
        if (changed) {
            try {
                PlayAccountStore.save(context, account);
            } catch (Exception e) {
                Log.w(TAG, "The Play session could not be saved", e);
            }
        }
    }

    private void checkin() throws PlayException {
        Request request = new Request.Builder()
                .url(BASE + "/checkin")
                .header("app", "com.google.android.gms")
                .header("User-Agent", device.authUserAgent())
                .post(RequestBody.create(device.checkinRequest(), MediaType.get("application/x-protobuffer")))
                .build();
        try {
            Proto.Message response = Proto.Message.parse(execute(request, true));
            long androidId = response.int64(7);
            if (androidId == 0) {
                throw new PlayException(PlayException.Kind.SERVER, "Google Play did not register this device");
            }
            account.gsfId = new BigInteger(Long.toUnsignedString(androidId)).toString(16);
            account.checkinConsistencyToken = response.string(12);
            account.deviceFingerprint = device.fingerprint;
        } catch (RuntimeException e) {
            throw new PlayException(PlayException.Kind.MALFORMED, "Malformed checkin response", e);
        }
    }

    private void uploadDeviceConfig() throws PlayException {
        byte[] body = new Proto.Writer().message(1, device.deviceConfiguration()).toByteArray();
        Request request = sign(new Request.Builder()
                .url(FDFE + "/uploadDeviceConfig")
                .post(RequestBody.create(body, PROTOBUF)))
                .build();
        Proto.Message payload = payload(execute(request, true));
        account.deviceConfigToken = payload.message(28).string(1);
    }

    private void mintAuthToken() throws PlayException {
        HttpUrl url = HttpUrl.get(BASE + "/auth").newBuilder()
                .addQueryParameter("androidId", account.gsfId)
                .addQueryParameter("sdk_version", String.valueOf(device.sdk))
                .addQueryParameter("Email", account.email)
                .addQueryParameter("google_play_services_version", String.valueOf(device.gmsVersion))
                .addQueryParameter("device_country", device.countryCode())
                .addQueryParameter("lang", device.languageCode())
                .addQueryParameter("callerSig", GOOGLE_CERT_SHA1)
                .addQueryParameter("app", "com.android.vending")
                .addQueryParameter("client_sig", GOOGLE_CERT_SHA1)
                .addQueryParameter("callerPkg", "com.google.android.gms")
                .addQueryParameter("Token", account.aasToken)
                .addQueryParameter("oauth2_foreground", "1")
                .addQueryParameter("token_request_options", "CAA4AVAB")
                .addQueryParameter("check_email", "1")
                .addQueryParameter("system_partition", "1")
                .addQueryParameter("droidguard_results", "null")
                .addQueryParameter("service", "oauth2:https://www.googleapis.com/auth/googleplay")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("app", "com.google.android.gms")
                .header("User-Agent", device.authUserAgent())
                .header("device", account.gsfId)
                .post(RequestBody.create(new byte[0], null))
                .build();
        Map<String, String> values = keyValues(execute(request, true));
        String token = values.get("Auth");
        if (TextUtils.isEmpty(token)) {
            throw new PlayException(PlayException.Kind.UNAUTHORIZED, "Google did not return a Play token");
        }
        account.authToken = token;
        long expiry = TOKEN_LIFETIME_FALLBACK_MS + System.currentTimeMillis();
        String expiresIn = values.get("ExpiresInDurationSec");
        String expiresAt = values.get("Expiry");
        try {
            if (expiresIn != null) {
                expiry = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Long.parseLong(expiresIn)) - 60_000;
            } else if (expiresAt != null) {
                expiry = TimeUnit.SECONDS.toMillis(Long.parseLong(expiresAt)) - 60_000;
            }
        } catch (NumberFormatException ignored) {
        }
        account.authTokenExpiry = expiry;
    }

    private static byte[] execute(Request request, boolean isAuth) throws PlayException {
        try (Response response = HTTP.newCall(request).execute()) {
            ResponseBody body = response.body();
            byte[] bytes = body == null ? new byte[0] : body.bytes();
            int code = response.code();
            if (response.isSuccessful()) return bytes;
            String reason = describeError(bytes, response.message(), code);
            PlayException.Kind kind;
            if (code == 429) {
                kind = PlayException.Kind.RATE_LIMITED;
            } else if (code >= 500) {
                kind = PlayException.Kind.SERVER;
            } else if (code == 401 || (isAuth && code == 403)) {
                kind = PlayException.Kind.UNAUTHORIZED;
            } else if (code == 403) {
                kind = PlayException.Kind.NOT_PURCHASED;
            } else if (code == 404) {
                kind = PlayException.Kind.NOT_AVAILABLE;
            } else if (isAuth) {
                kind = PlayException.Kind.UNAUTHORIZED;
            } else {
                kind = PlayException.Kind.UNKNOWN;
            }
            throw new PlayException(kind, reason, null, code);
        } catch (IOException e) {
            throw new PlayException(PlayException.Kind.NETWORK, e.getMessage() == null ? "Network error" : e.getMessage(), e);
        }
    }

    /** /auth answers errors in text ("Error=BadAuthentication"); anything else is reported by status. */
    private static String describeError(byte[] body, String message, int code) {
        String text = new String(body, StandardCharsets.UTF_8);
        Map<String, String> values = keyValues(body);
        String error = values.get("Error");
        if (error != null && error.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) return error;
        if (!text.isEmpty() && text.length() < 200 && text.chars().allMatch(c -> (c >= 0x20 && c < 0x7F) || c == '\n')) {
            return text.trim();
        }
        return TextUtils.isEmpty(message) ? "HTTP " + code : "HTTP " + code + " " + message;
    }

    private static Map<String, String> keyValues(byte[] body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : new String(body, StandardCharsets.UTF_8).split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
        }
        return values;
    }

    /** Play sends hashes as (URL-safe, maybe unpadded) base64; downloads verify them as hex. */
    static String hex(String base64) {
        if (TextUtils.isEmpty(base64)) return null;
        try {
            byte[] raw = Base64.decode(base64.replace('-', '+').replace('_', '/'), Base64.DEFAULT);
            StringBuilder out = new StringBuilder(raw.length * 2);
            for (byte b : raw) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
