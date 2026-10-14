package app.pollydrop;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class GeoCountryTest {
    @Test void realCountryDatabaseHandlesBothAddressFamiliesAndDoesNotResolveHostnames() throws Exception {
        try (var country = new TestCountry()) {
            assertEquals("RU", country.geo.country("5.255.255.5"));
            assertEquals("RU", country.geo.country("2a02:6b8::2"));
            assertEquals("US", country.geo.country("8.8.8.8"));
            assertEquals("ZZ", country.geo.country("127.0.0.1"));
            assertEquals("ZZ", country.geo.country("example.com"));
            assertEquals("ZZ", country.geo.country("999.2.3.4"));
        }
    }
    @Test void countrySelectsLanguageAndIgnoresForwardedHeadersFromUntrustedPublicPeers() throws Exception {
        try (var country = new TestCountry()) {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("172.19.0.5"); request.addHeader("X-Real-IP", "5.255.255.5");
            assertEquals("ru", country.geo.preference(request).get("language"));
            request.setRemoteAddr("8.8.8.8");
            assertEquals("en", country.geo.preference(request).get("language"));
            request.setRemoteAddr("127.0.0.1"); request.removeHeader("X-Real-IP");
            assertEquals("en", country.geo.preference(request).get("language"));
        }
    }
    static class TestCountry implements AutoCloseable {
        final GeoCountry geo = new GeoCountry();
        TestCountry() throws Exception { }
        public void close() throws Exception { geo.close(); }
    }
}
