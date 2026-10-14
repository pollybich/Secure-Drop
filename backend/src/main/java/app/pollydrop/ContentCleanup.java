package app.pollydrop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.slf4j.*;
import java.time.Instant;
import java.util.UUID;
import java.io.IOException;

@Service
public class ContentCleanup {
    private static final Logger log=LoggerFactory.getLogger(ContentCleanup.class);
    private final TransferRepository repository;
    private final CipherStorage storage;
    public ContentCleanup(TransferRepository repository,CipherStorage storage){this.repository=repository;this.storage=storage;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void discard(UUID id){
        repository.lockId(id).ifPresent(t->{
            boolean unavailable=t.revoked || !t.expiresAt.isAfter(Instant.now()) || t.downloads>=t.maxDownloads;
            if(!unavailable || t.purgedAt!=null)return;
            try {storage.delete(id);t.purgedAt=Instant.now();repository.saveAndFlush(t);}
            catch(IOException e){log.warn("transfer_cleanup_pending id={}",id);}
        });
    }
}
