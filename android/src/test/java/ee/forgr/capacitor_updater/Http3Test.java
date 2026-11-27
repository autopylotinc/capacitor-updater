package ee.forgr.capacitor_updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.Test;

public class Http3Test {

    /** Stands in for HttpEngine's HttpURLConnection. */
    static class FakeConnection extends HttpURLConnection {

        final Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
        final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        int code = 200;
        String body = "";
        boolean disconnected;

        FakeConnection(URL url) {
            super(url);
            responseHeaders.put(null, List.of("HTTP/1.1 200 OK"));
        }

        FakeConnection header(String name, String value) {
            responseHeaders.computeIfAbsent(name, (k) -> new ArrayList<>()).add(value);
            return this;
        }

        @Override
        public void addRequestProperty(String key, String value) {
            requestHeaders.computeIfAbsent(key, (k) -> new ArrayList<>()).add(value);
        }

        @Override
        public int getResponseCode() {
            return code;
        }

        @Override
        public String getResponseMessage() {
            return code == 200 ? "OK" : "Not Found";
        }

        @Override
        public String getHeaderField(String name) {
            List<String> values = responseHeaders.get(name);
            return values == null ? null : values.get(values.size() - 1);
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return responseHeaders;
        }

        @Override
        public InputStream getInputStream() {
            if (code >= 400) {
                throw new AssertionError("error responses must be read from the error stream");
            }
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {}
    }

    private static final String FILE_URL = "https://live-updates.example.com/1.0.0/files/abc.js";

    @Test
    public void getIsSentOverTheEngineWithHeadersAndTimeouts() throws Exception {
        FakeConnection conn = new FakeConnection(new URL(FILE_URL));
        conn.code = 206;
        conn.body = "partial";
        conn.header("Content-Range", "bytes 10-16/17").header("Content-Length", "7").header("Content-Type", "text/javascript");
        Request request = new Request.Builder()
            .url(FILE_URL)
            .header("Range", "bytes=10-")
            .header("User-Agent", "CapacitorUpdater/1")
            .build();

        try (Response response = Http3.execute((url) -> conn, request, 1234, 5678)) {
            assertEquals(206, response.code());
            assertEquals("bytes 10-16/17", response.header("Content-Range"));
            assertEquals(7, response.body().contentLength());
            assertEquals("text/javascript", response.body().contentType().toString());
            assertEquals("partial", response.body().string());
        }
        assertEquals(List.of("bytes=10-"), conn.requestHeaders.get("Range"));
        assertEquals(List.of("CapacitorUpdater/1"), conn.requestHeaders.get("User-Agent"));
        assertEquals(1234, conn.getConnectTimeout());
        assertEquals(5678, conn.getReadTimeout());
        assertTrue(conn.getInstanceFollowRedirects());
        assertTrue("closing the body releases the engine request", conn.disconnected);
    }

    @Test
    public void decodedBodiesDropContentEncodingAndLength() throws Exception {
        FakeConnection conn = new FakeConnection(new URL(FILE_URL));
        conn.body = "console.log(1)";
        conn.header("Content-Encoding", "gzip").header("Content-Length", "3");
        Request request = new Request.Builder().url(FILE_URL).build();

        try (Response response = Http3.execute((url) -> conn, request, 1, 1)) {
            assertNull(response.header("Content-Encoding"));
            assertNull(response.header("Content-Length"));
            assertEquals(-1, response.body().contentLength());
            assertEquals("console.log(1)", response.body().string());
        }
    }

    @Test
    public void errorResponsesReadTheErrorStream() throws Exception {
        FakeConnection conn = new FakeConnection(new URL(FILE_URL));
        conn.code = 404;
        conn.body = "missing";
        Request request = new Request.Builder().url(FILE_URL).build();

        try (Response response = Http3.execute((url) -> conn, request, 1, 1)) {
            assertEquals(404, response.code());
            assertFalse(response.isSuccessful());
            assertEquals("missing", response.body().string());
        }
    }

    @Test
    public void failuresDisconnect() throws Exception {
        FakeConnection conn = new FakeConnection(new URL(FILE_URL)) {
            @Override
            public int getResponseCode() {
                throw new IllegalStateException("boom");
            }
        };
        try {
            Http3.execute((url) -> conn, new Request.Builder().url(FILE_URL).build(), 1, 1);
            fail("expected the failure to propagate");
        } catch (IllegalStateException expected) {
            assertTrue(conn.disconnected);
        }
    }

    @Test
    public void jsonPostsStayOnOkHttp() throws Exception {
        // Built the way CapgoUpdater.makeJsonRequest builds it: the Content-Type lives only on
        // the RequestBody, and OkHttp's own transport turns it into a header.
        Request post = new Request.Builder()
            .url("https://api.example.com/updates")
            .post(RequestBody.create("{}", MediaType.get("application/json")))
            .build();
        Request put = new Request.Builder()
            .url("https://api.example.com/channel_self")
            .put(RequestBody.create("{}", MediaType.get("application/json")))
            .build();
        Http3.Opener failing = (url) -> {
            throw new AssertionError("requests with a body must not reach HttpEngine");
        };
        for (Request request : List.of(post, put)) {
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            Response proceeded = new Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_2).code(200).message("OK").build();
            when(chain.request()).thenReturn(request);
            when(chain.proceed(request)).thenReturn(proceeded);

            assertSame(proceeded, new Http3.EngineInterceptor(() -> failing).intercept(chain));
            verify(chain).proceed(request);
        }
    }

    @Test
    public void withoutAnEngineEverythingStaysOnOkHttp() throws Exception {
        Request request = new Request.Builder().url(FILE_URL).build();
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        Response proceeded = new Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_2).code(200).message("OK").build();
        when(chain.request()).thenReturn(request);
        when(chain.proceed(request)).thenReturn(proceeded);

        assertSame(proceeded, new Http3.EngineInterceptor(() -> null).intercept(chain));
        verify(chain).proceed(request);
    }
}
