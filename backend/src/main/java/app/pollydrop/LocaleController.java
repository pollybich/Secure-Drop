package app.pollydrop;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LocaleController {
    private final GeoCountry country;
    public LocaleController(GeoCountry country) { this.country = country; }
    @GetMapping("/locale") public Map<String,String> locale(HttpServletRequest request) { return country.preference(request); }
}
