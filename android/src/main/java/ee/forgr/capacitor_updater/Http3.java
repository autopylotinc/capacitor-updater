package ee.forgr.capacitor_updater;

import android.content.Context;
import android.net.http.HttpEngine;
import android.os.Build;
import android.os.ext.SdkExtensions;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresExtension;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

/**
 * Sends downloads over the platform HttpEngine (Cronet in the OS) where the device has it, so
 * they use HTTP/3 when the network allows and fall back to HTTP/2 when it doesn't. HttpEngine
 * does that fallback itself. Devices without it keep the JDK/OkHttp transports unchanged.
 */
final class Http3 {

    interface Opener {
        URLConnection open(URL url) throws IOException;
    }

    // Not java.util.function.Supplier: that needs API 24 and minSdk is 23.
    interface OpenerSource {
        @Nullable
        Opener get();
    }

    // null until init() finds HttpEngine on this device.
    private static volatile Opener engineOpener;
    private static boolean initialized;

    private Http3() {}

    static synchronized void init(@NonNull Context context, @Nullable Logger logger) {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!isHttpEngineAvailable()) {
            log(logger, "HttpEngine not available on this device, staying on HTTP/2");
            return;
        }
        try {
            engineOpener = buildEngine(context.getApplicationContext());
            log(logger, "HttpEngine ready, HTTP/3 enabled for downloads");
        } catch (RuntimeException | LinkageError e) {
            // Fall back to the existing transports; that is the point of HTTP/2 fallback.
            // LinkageError covers a device that reports the extension but can't load the engine.
            log(logger, "HttpEngine failed to start, staying on HTTP/2: " + e.getMessage());
        }
    }

    @Nullable
    static Opener engineOpener() {
        return engineOpener;
    }

    static URLConnection openConnection(URL url) throws IOException {
        Opener opener = engineOpener;
        return opener != null ? opener.open(url) : url.openConnection();
    }

    // HttpEngine is API 34, and on API 31-33 ships through the S extension, level 7.
    private static boolean isHttpEngineAvailable() {
        return (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7)
        );
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    private static Opener buildEngine(Context context) {
        File storage = new File(context.getCacheDir(), "capgo_httpengine");
        if (!storage.isDirectory() && !storage.mkdirs()) {
            throw new IllegalStateException("cannot create " + storage.getAbsolutePath());
        }
        // DISK_NO_HTTP persists Alt-Svc and QUIC state across launches without caching any
        // response, so the first request of a new process can already go over HTTP/3.
        HttpEngine engine = new HttpEngine.Builder(context)
            .setStoragePath(storage.getAbsolutePath())
            .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 1024 * 1024)
            .setEnableQuic(true)
            .setEnableHttp2(true)
            .build();
        return engine::openConnection;
    }

    private static void log(@Nullable Logger logger, String message) {
        if (logger != null) {
            logger.info(message);
        }
    }

    /**
     * Runs body-less GETs (the manifest file downloads) on HttpEngine. Everything else goes on
     * through OkHttp, so request bodies keep the Content-Type and write timeout OkHttp's own
     * transport gives them.
     */
    static final class EngineInterceptor implements Interceptor {

        private final OpenerSource opener;

        EngineInterceptor(OpenerSource opener) {
            this.opener = opener;
        }

        @NonNull
        @Override
        public Response intercept(@NonNull Chain chain) throws IOException {
            Request request = chain.request();
            Opener current = opener.get();
            if (current == null || !"GET".equals(request.method()) || request.body() != null) {
                return chain.proceed(request);
            }
            return execute(current, request, chain.connectTimeoutMillis(), chain.readTimeoutMillis());
        }
    }

    static Response execute(Opener opener, Request request, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) opener.open(request.url().url());
        try {
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setInstanceFollowRedirects(true);
            Headers requestHeaders = request.headers();
            for (int i = 0; i < requestHeaders.size(); i++) {
                conn.addRequestProperty(requestHeaders.name(i), requestHeaders.value(i));
            }

            int code = conn.getResponseCode();
            // HttpEngine decodes Content-Encoding itself, so the body no longer matches those
            // headers. Drop them, as OkHttp does when it gunzips transparently.
            boolean decoded = conn.getHeaderField("Content-Encoding") != null;
            Headers.Builder headers = new Headers.Builder();
            for (Map.Entry<String, List<String>> field : conn.getHeaderFields().entrySet()) {
                String name = field.getKey();
                if (name == null) {
                    continue; // status line
                }
                if (decoded && (name.equalsIgnoreCase("Content-Encoding") || name.equalsIgnoreCase("Content-Length"))) {
                    continue;
                }
                for (String value : field.getValue()) {
                    headers.addUnsafeNonAscii(name, value);
                }
            }
            Headers responseHeaders = headers.build();

            InputStream stream = code >= HttpURLConnection.HTTP_BAD_REQUEST ? conn.getErrorStream() : conn.getInputStream();
            Source source = stream == null ? new Buffer() : Okio.source(stream);
            BufferedSource body = Okio.buffer(
                new ForwardingSource(source) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            conn.disconnect();
                        }
                    }
                }
            );
            String contentType = responseHeaders.get("Content-Type");
            String contentLength = responseHeaders.get("Content-Length");
            String message = conn.getResponseMessage();
            return new Response.Builder()
                .request(request)
                // HttpURLConnection does not expose the negotiated protocol and no caller reads
                // it. This is a placeholder, not what went over the wire.
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message != null ? message : "")
                .headers(responseHeaders)
                .body(
                    ResponseBody.create(
                        body,
                        contentType != null ? MediaType.parse(contentType) : null,
                        contentLength != null ? Long.parseLong(contentLength) : -1
                    )
                )
                .build();
        } catch (IOException | RuntimeException e) {
            conn.disconnect();
            throw e;
        }
    }
}
