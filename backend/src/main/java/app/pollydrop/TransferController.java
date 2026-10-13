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
    private final TransferService service;private final RuntimeGuard guard;private final DeliveryService delivery;private final DownloadTickets tickets;
    public TransferController(TransferService service,RuntimeGuard guard,DeliveryService delivery,DownloadTickets tickets){this.service=service;this.guard=guard;this.delivery=delivery;this.tickets=tickets;}
    @GetMapping("/health") public Map<String,String> health(){if(!guard.ready())throw new ApiFailure(503,"NOT_READY","Сервис запускается.");return Map.of("status","ok","version","0.1.0-beta.2");}
    @PostMapping(value="/transfers",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TransferService.Created create(@RequestPart("options") TransferService.Options options,@RequestPart(value="file",required=false)MultipartFile file)throws IOException {
        requireReady();return service.create(options,file==null?null:file.getBytes(),file==null?null:file.getOriginalFilename());
    }
    @GetMapping("/transfers/{token}")public TransferService.View metadata(@PathVariable String token){requireReady();return service.publicView(token);}
    @GetMapping("/manage/{token}")public TransferService.View manage(@PathVariable String token){requireReady();return service.manageView(token);}
    @DeleteMapping("/manage/{token}")@ResponseStatus(HttpStatus.NO_CONTENT)public void revoke(@PathVariable String token){requireReady();service.revoke(token);}
    public record Access(String password) {}
    @PostMapping("/transfers/{token}/download-ticket")
    public DownloadTickets.Issued ticket(@PathVariable String token,@RequestBody(required=false)Access access,HttpServletRequest request){
        requireReady();return tickets.issue(token,access==null?null:access.password,request.getRemoteAddr());
    }
    @GetMapping("/downloads/{secret}/status")
    public Map<String,String> ticketStatus(@PathVariable String secret){requireReady();return Map.of("state",tickets.status(secret).name());}
    @GetMapping("/downloads/{secret}")
    public void nativeDownload(@PathVariable String secret,HttpServletRequest request,HttpServletResponse response)throws IOException {
        requireReady();
        // Spring maps HEAD to GET by default. A preview must never claim a ticket or spend a download.
        if(!"GET".equals(request.getMethod()))throw new ApiFailure(405,"METHOD_NOT_ALLOWED","Используйте кнопку получения файла.");
        DownloadTickets.Ticket ticket=tickets.begin(secret);boolean completed=false;
        try {send(service.reserveAuthorized(ticket.id),response);completed=true;}
        catch(IOException e){if(!response.isCommitted()){resetFailure(response);throw e;}}
        finally {tickets.finish(ticket,completed);}
    }
    @PostMapping("/transfers/{token}/download")
    public void download(@PathVariable String token,@RequestBody(required=false)Access access,HttpServletRequest request,HttpServletResponse response)throws IOException {
        requireReady();
        try {send(service.reserve(token,access==null?null:access.password,request.getRemoteAddr()),response);}
        catch(IOException e){if(!response.isCommitted()){resetFailure(response);throw e;}}
    }
    private void send(TransferService.Lease lease,HttpServletResponse response)throws IOException {
        try {
            response.setContentType(lease.mediaType());response.setContentLengthLong(lease.size());
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(lease.name(),StandardCharsets.UTF_8).build().toString());
            delivery.send(lease,response.getOutputStream());
        }catch(IOException e){service.finish(lease,false);throw e;}
    }
    private void resetFailure(HttpServletResponse response){
        response.reset();response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Frame-Options","DENY");
    }
    private void requireReady(){if(!guard.ready())throw new ApiFailure(503,"NOT_READY","Сервис запускается. Попробуйте через несколько секунд.");}
}
