package app.pollydrop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.*;
import org.slf4j.*;
import java.time.Instant;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

@Service
public class TransferService {
    private static final Logger log=LoggerFactory.getLogger(TransferService.class);
    private final TransferRepository repository;
    private final CipherStorage storage;
    private final PasswordGate passwords;
    private final long maxBytes;
    public TransferService(TransferRepository repository,CipherStorage storage,PasswordGate passwords,@Value("${app.max-bytes}") long maxBytes) {
        this.repository=repository;this.storage=storage;this.passwords=passwords;this.maxBytes=maxBytes;
    }
    public record View(String kind,String name,long bytes,Instant createdAt,Instant expiresAt,int maxDownloads,int downloads,int remaining,boolean passwordProtected,String state) {}
    public record Created(String readToken,String manageToken,View transfer) {}
    public record Lease(UUID id,UUID leaseId,String name,String mediaType,long size,Instant expiresAt) {}
    public record Options(String kind,String text,String name,int ttlSeconds,int maxDownloads,String password) {}
    @Transactional
    public Created create(Options options,byte[] file,String filename) throws IOException {
        if(options==null)throw new ApiFailure(400,"INVALID_OPTIONS","Укажите параметры передачи.");
        if(options.ttlSeconds<600 || options.ttlSeconds>604800 || options.maxDownloads<1 || options.maxDownloads>10) throw new ApiFailure(400,"INVALID_OPTIONS","Проверьте срок и число получений.");
        boolean text="TEXT".equals(options.kind);
        if(!text && !"FILE".equals(options.kind)) throw new ApiFailure(400,"INVALID_KIND","Выберите файл или текст.");
        byte[] contents=text?(options.text==null?new byte[0]:options.text.getBytes(StandardCharsets.UTF_8)):file;
        if(contents==null || contents.length==0) throw new ApiFailure(400,"EMPTY_CONTENT","Добавьте файл или текст.");
        if(contents.length>(text?65536:maxBytes)) throw new ApiFailure(413,"TOO_LARGE",text?"Текст должен быть не больше 64 КБ.":"Файл должен быть не больше 25 МБ.");
        if(options.password!=null && options.password.length()>128) throw new ApiFailure(400,"INVALID_PASSWORD","Пароль слишком длинный.");
        String read=Tokens.newToken(),manage=Tokens.newToken();
        Transfer t=new Transfer();t.id=UUID.randomUUID();t.readHash=Tokens.hash(read);t.manageHash=Tokens.hash(manage);t.kind=text?"TEXT":"FILE";
        String name=text?(options.name==null||options.name.isBlank()?"Текстовая передача":options.name):(filename==null?"Файл":filename);
        name=name.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","").strip();
        t.name=name.isBlank()?"Передача":name.substring(0,Math.min(name.length(),240));
        t.mediaType=text?"text/plain; charset=utf-8":"application/octet-stream";t.byteSize=contents.length;t.createdAt=Instant.now();t.expiresAt=t.createdAt.plusSeconds(options.ttlSeconds);t.maxDownloads=options.maxDownloads;
        if(options.password!=null && !options.password.isEmpty())t.passwordHash=passwords.encode(options.password);
        storage.write(t.id,contents);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{storage.delete(t.id);}catch(IOException ignored){}}});
        repository.saveAndFlush(t);log.info("transfer_created id={} kind={}",t.id,t.kind);
        return new Created(read,manage,view(t,true));
    }
    @Transactional(readOnly=true)
    public View publicView(String token) { return view(repository.findByReadHash(Tokens.hash(token)).orElseThrow(TransferService::missing),false); }
    @Transactional(readOnly=true)
    public View manageView(String token) { return view(repository.findByManageHash(Tokens.hash(token)).orElseThrow(TransferService::missing),true); }
    @Transactional
    public Lease reserve(String token,String password,String client) {
        Transfer t=repository.lockRead(Tokens.hash(token)).orElseThrow(TransferService::missing);
        requireAvailable(t);passwords.check(t,password,client);
        if(t.activeLease!=null)throw new ApiFailure(409,"IN_PROGRESS","Передача уже открыта. Дождитесь завершения или попробуйте снова.");
        t.activeLease=UUID.randomUUID();repository.saveAndFlush(t);
        return new Lease(t.id,t.activeLease,t.name,t.mediaType,t.byteSize,t.expiresAt);
    }
    @Transactional
    public void finish(Lease lease,boolean completed) {
        Transfer t=repository.lockId(lease.id).orElseThrow(TransferService::missing);
        if(!lease.leaseId.equals(t.activeLease)) return;
        t.activeLease=null;
        if(completed)t.downloads++;
        repository.saveAndFlush(t);log.info(completed?"transfer_received id={}":"transfer_interrupted id={}",t.id);
    }
    @Transactional
    public void revoke(String manageToken) {
        Transfer t=repository.lockManage(Tokens.hash(manageToken)).orElseThrow(TransferService::missing);t.revoked=true;repository.saveAndFlush(t);log.info("transfer_revoked id={}",t.id);
    }
    @Transactional(readOnly=true)
    public boolean interrupted(UUID id,UUID lease) { return repository.findById(id).map(t->t.revoked || !t.expiresAt.isAfter(Instant.now()) || !lease.equals(t.activeLease)).orElse(true); }
    @Transactional
    public void recover() { repository.clearAbandonedLeases(); }
    public void cleanup() {
        for(UUID id:repository.cleanupCandidates(Instant.now())) {
            // Purged rows remain as management metadata; a missing file is a successful deletion.
            try { storage.delete(id); markPurged(id); } catch(IOException e) { log.warn("transfer_cleanup_pending id={}",id); }
        }
    }
    @Transactional
    public void markPurged(UUID id) { repository.findById(id).ifPresent(t->{t.purgedAt=Instant.now();repository.save(t);}); }
    private static ApiFailure missing(){return new ApiFailure(404,"NOT_FOUND","Этой передачи не существует.");}
    private static void requireAvailable(Transfer t) {
        if(t.revoked)throw new ApiFailure(410,"REVOKED","Отправитель отозвал передачу.");
        if(!t.expiresAt.isAfter(Instant.now()))throw new ApiFailure(410,"EXPIRED","Время этой передачи истекло.");
        if(t.downloads>=t.maxDownloads || t.purgedAt!=null)throw new ApiFailure(410,"EXHAUSTED","Все получения уже использованы.");
    }
    private static View view(Transfer t,boolean owner) {
        String state=t.revoked?"REVOKED":!t.expiresAt.isAfter(Instant.now())?"EXPIRED":t.downloads>=t.maxDownloads||t.purgedAt!=null?"EXHAUSTED":t.activeLease!=null?"IN_PROGRESS":"AVAILABLE";
        return new View(t.kind,!owner&&t.passwordHash!=null?"Защищённая передача":t.name,t.byteSize,t.createdAt,t.expiresAt,t.maxDownloads,t.downloads,Math.max(0,t.maxDownloads-t.downloads),t.passwordHash!=null,state);
    }
}
