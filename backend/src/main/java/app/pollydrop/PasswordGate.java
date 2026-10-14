package app.pollydrop;

import org.springframework.stereotype.Component;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PasswordGate {
    private final Argon2PasswordEncoder encoder=new Argon2PasswordEncoder(16,32,1,65536,3);
    private final ConcurrentHashMap<String,Attempt> attempts=new ConcurrentHashMap<>();
    private record Attempt(int failures, Instant until) {}
    public String encode(String password) { return encoder.encode(password); }
    public void check(Transfer transfer, String password, String client) {
        if(transfer.passwordHash==null) return;
        String k=transfer.readHash+":"+client;Instant now=Instant.now();
        Attempt previous=attempts.get(k);
        if(previous!=null && previous.failures>=5 && previous.until.isAfter(now)) throw new ApiFailure(429,"RATE_LIMITED","Слишком много попыток. Попробуйте через 15 минут.");
        if(password==null || password.isEmpty()) throw new ApiFailure(401,"PASSWORD_REQUIRED","Введите пароль передачи.");
        if(password.length()>128 || !encoder.matches(password,transfer.passwordHash)) {
            attempts.compute(k,(key,value)->value==null||!value.until.isAfter(now)?new Attempt(1,now.plusSeconds(900)):new Attempt(value.failures+1,value.failures==4?now.plusSeconds(900):value.until));
            throw new ApiFailure(401,"WRONG_PASSWORD","Пароль не подошёл. Попробуйте ещё раз.");
        }
        attempts.remove(k);
    }
    @Scheduled(fixedDelay=60000)
    void prune() { Instant now=Instant.now();attempts.entrySet().removeIf(e->!e.getValue().until.isAfter(now)); }
}
