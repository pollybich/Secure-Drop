package app.pollydrop;

import com.maxmind.db.Reader;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;

@Component
public class GeoCountry {
    private final Reader reader;
    public GeoCountry() throws IOException {
        reader = new Reader(new ClassPathResource("geoip-country.mmdb").getInputStream());
    }
    public String country(String literal) {
        if (literal == null || !(literal.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || literal.contains(":") && literal.matches("[0-9a-fA-F:.]+"))) return "ZZ";
        try {
            var address = InetAddress.getByName(literal);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) return "ZZ";
            var record = reader.get(address, Map.class);
            Object code = record == null ? null : record.get("country_code");
            return code instanceof String value && value.matches("[A-Z]{2}") ? value : "ZZ";
        } catch (IOException e) { return "ZZ"; }
    }
    public Map<String,String> preference(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        // Backend is private. Only the local/private ingress can supply X-Real-IP;
        // Nginx obtains it from Caddy's sanitized forwarding header.
        try {
            var peer = InetAddress.getByName(remote);
            if (peer.isLoopbackAddress() || peer.isSiteLocalAddress()) {
                String forwarded = request.getHeader("X-Real-IP");
                if (forwarded != null) remote = forwarded;
            }
        } catch (IOException ignored) { }
        String country = country(remote);
        return Map.of("country", country, "language", "RU".equals(country) ? "ru" : "en");
    }
    @PreDestroy public void close() throws IOException { reader.close(); }
}
