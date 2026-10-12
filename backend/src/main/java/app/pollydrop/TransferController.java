package app.pollydrop;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransferController {
    private final TransferService service;private final RuntimeGuard guard;private final DeliveryService delivery;
    public TransferController(TransferService service,RuntimeGuard guard,DeliveryService delivery){this.service=service;this.guard=guard;this.delivery=delivery;}
    @GetMapping("/health") public Map<String,String> health(){if(!guard.ready())throw new ApiFailure(503,"NOT_READY","Сервис запускается.");return Map.of("status","ok","version","0.1.0-beta.1");}
    @PostMapping(value="/transfers",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TransferService.Created create(@RequestPart("options") TransferService.Options options,@RequestPart(value="file",required=false)MultipartFile file)throws IOException {
        requireReady();return service.create(options,file==null?null:file.getBytes(),file==null?null:file.getOriginalFilename());
    }
    @GetMapping("/transfers/{token}")public TransferService.View metadata(@PathVariable String token){requireReady();return service.publicView(token);}
    @GetMapping("/manage/{token}")public TransferService.View manage(@PathVariable String token){requireReady();return service.manageView(token);}
    @DeleteMapping("/manage/{token}")@ResponseStatus(HttpStatus.NO_CONTENT)public void revoke(@PathVariable String token){requireReady();service.revoke(token);}
    public record Access(String password) {}
    @PostMapping("/transfers/{token}/download")
    public void download(@PathVariable String token,@RequestBody(required=false)Access access,HttpServletRequest request,HttpServletResponse response)throws IOException {
        requireReady();TransferService.Lease lease=service.reserve(token,access==null?null:access.password,request.getRemoteAddr());
        try {
            response.setContentType(lease.mediaType());response.setContentLengthLong(lease.size());
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(lease.name(),StandardCharsets.UTF_8).build().toString());
            delivery.send(lease,response.getOutputStream());
        }catch(IOException e){service.finish(lease,false);if(!response.isCommitted())throw e;}
    }
    private void requireReady(){if(!guard.ready())throw new ApiFailure(503,"NOT_READY","Сервис запускается. Попробуйте через несколько секунд.");}
}
