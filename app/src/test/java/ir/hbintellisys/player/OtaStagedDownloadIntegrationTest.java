package ir.hbintellisys.player;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

/** Real staging files and deterministic fake HTTPS responses, no network or device access. */
public class OtaStagedDownloadIntegrationTest {
    static class Response {
        final int status; final byte[] body; final long declared; final String etag, range;
        Response(int status, byte[] body, long declared, String etag, String range) {
            this.status=status; this.body=body; this.declared=declared; this.etag=etag; this.range=range;
        }
    }
    static class FakeHttp extends HttpURLConnection {
        final Response r; final Map<String,String> request = new HashMap<>();
        FakeHttp(URL url, Response r) { super(url); this.r=r; }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
        @Override public void setRequestProperty(String key,String value) { request.put(key,value); }
        @Override public int getResponseCode() { return r.status; }
        @Override public String getHeaderField(String name) {
            if ("ETag".equalsIgnoreCase(name)) return r.etag;
            if ("Content-Range".equalsIgnoreCase(name)) return r.range;
            return null;
        }
        @Override public long getContentLengthLong() { return r.declared; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(r.body); }
    }
    static class FakeServer extends URLStreamHandler {
        final Queue<Response> responses = new ArrayDeque<>();
        final List<FakeHttp> connections = new ArrayList<>();
        @Override protected URLConnection openConnection(URL url) {
            FakeHttp c = new FakeHttp(url, responses.remove()); connections.add(c); return c;
        }
        URL url() throws Exception { return new URL(null,"https://ota.example.invalid/file.apk",this); }
    }
    static String sha(byte[] bytes) throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder b=new StringBuilder();
        for(byte x:hash) b.append(String.format(java.util.Locale.US,"%02x",x&255));
        return b.toString();
    }
    static File dir() throws Exception { return Files.createTempDirectory("hb-ota-test-").toFile(); }

    @Test public void interruptedThen206Resume() throws Exception {
        byte[] full="abcdefghij".getBytes("UTF-8");
        FakeServer server=new FakeServer(); File d=dir();
        server.responses.add(new Response(200,Arrays.copyOfRange(full,0,4),10,"\"v1\"",null));
        try { OtaStagedDownloader.stage(server.url(),d,"release8",sha(full)); fail("Expected truncation"); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("Truncated")); }
        assertEquals(4,new File(d,"release8.part").length());
        server.responses.add(new Response(206,Arrays.copyOfRange(full,4,10),6,"\"v1\"","bytes 4-9/10"));
        File apk=OtaStagedDownloader.stage(server.url(),d,"release8",sha(full));
        assertArrayEquals(full,Files.readAllBytes(apk.toPath()));
        assertEquals("bytes=4-",server.connections.get(1).request.get("Range"));
        assertEquals("\"v1\"",server.connections.get(1).request.get("If-Range"));
        assertFalse(new File(d,"release8.part").exists());
    }

    @Test public void serverChangedTo200Restarts() throws Exception {
        byte[] full="replacement".getBytes("UTF-8"); File d=dir();
        Files.write(new File(d,"release8.part").toPath(),"old".getBytes("UTF-8"));
        Files.write(new File(d,"release8.etag").toPath(),"\"old\"".getBytes("UTF-8"));
        FakeServer server=new FakeServer();
        server.responses.add(new Response(200,full,full.length,"\"new\"",null));
        assertArrayEquals(full,Files.readAllBytes(OtaStagedDownloader.stage(server.url(),d,"release8",sha(full)).toPath()));
    }

    @Test public void range416ClearsPartial() throws Exception {
        File d=dir(); Files.write(new File(d,"release8.part").toPath(),new byte[]{1,2});
        Files.write(new File(d,"release8.etag").toPath(),"\"v1\"".getBytes("UTF-8"));
        FakeServer server=new FakeServer(); server.responses.add(new Response(416,new byte[0],0,"\"v1\"",null));
        try { OtaStagedDownloader.stage(server.url(),d,"release8",sha(new byte[]{1})); fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("416")); }
        assertFalse(new File(d,"release8.part").exists());
    }

    @Test public void invalidResumeRangeClearsPartial() throws Exception {
        File d=dir(); Files.write(new File(d,"release8.part").toPath(),new byte[]{1,2});
        Files.write(new File(d,"release8.etag").toPath(),"\"v1\"".getBytes("UTF-8"));
        FakeServer server=new FakeServer();
        server.responses.add(new Response(206,new byte[]{3},1,"\"v1\"","bytes 0-0/3"));
        try { OtaStagedDownloader.stage(server.url(),d,"release8",sha(new byte[]{1,2,3})); fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("Content-Range")); }
        assertFalse(new File(d,"release8.part").exists());
    }

    @Test public void badDigestNeverProducesApk() throws Exception {
        FakeServer server=new FakeServer(); File d=dir();
        server.responses.add(new Response(200,new byte[]{9,8,7},3,"\"v1\"",null));
        try { OtaStagedDownloader.stage(server.url(),d,"release8",sha(new byte[]{1,2,3})); fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("SHA256")); }
        assertFalse(new File(d,"release8.apk").exists());
        assertFalse(new File(d,"release8.part").exists());
    }
}
