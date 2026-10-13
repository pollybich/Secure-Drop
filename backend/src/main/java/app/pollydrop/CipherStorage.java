package app.pollydrop;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.io.IOException;

@Component
public class CipherStorage {
    private final Path root;
    private final SecretKeySpec key;
    private final long quota;
    private final SecureRandom random = new SecureRandom();
    public CipherStorage(@Value("${app.storage-dir}") String directory, @Value("${app.master-key}") String encoded, @Value("${app.storage-quota}") long quota) throws IOException {
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(encoded); } catch(IllegalArgumentException e) { throw new IllegalStateException("APP_MASTER_KEY must be a base64-encoded 32-byte key"); }
        if(bytes.length!=32) throw new IllegalStateException("APP_MASTER_KEY must contain 32 bytes");
        this.root=Path.of(directory).toAbsolutePath().normalize(); this.key=new SecretKeySpec(bytes,"AES"); this.quota=quota;
        Files.createDirectories(root);
    }
    private Path path(UUID id) { return root.resolve(id+".enc"); }
    public synchronized void write(UUID id, byte[] plaintext) throws IOException {
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(plaintext);
            long used=0;
            try(var files=Files.list(root)) { for(Path p:files.toList()) if(Files.isRegularFile(p)) used+=Files.size(p); }
            if(used+13L+encrypted.length>quota) throw new ApiFailure(507,"STORAGE_FULL","Хранилище заполнено. Попробуйте позже.");
            byte[] output=new byte[13+encrypted.length]; output[0]=1;System.arraycopy(nonce,0,output,1,12);System.arraycopy(encrypted,0,output,13,encrypted.length);
            Path temp=root.resolve(id+".tmp");
            try { Files.write(temp,output,StandardOpenOption.CREATE_NEW); Files.move(temp,path(id),StandardCopyOption.ATOMIC_MOVE); }
            finally { Files.deleteIfExists(temp); }
        } catch(GeneralSecurityException e) { throw new IOException("Cannot encrypt transfer",e); }
    }
    public byte[] read(UUID id) throws IOException {
        byte[] input=Files.readAllBytes(path(id));
        if(input.length<29 || input[0]!=1) throw new IOException("Invalid encrypted data");
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(input,1,13)));
            cipher.updateAAD(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return cipher.doFinal(input,13,input.length-13);
        } catch(GeneralSecurityException e) { throw new IOException("Encrypted data integrity check failed",e); }
    }
    public synchronized void delete(UUID id) throws IOException { Files.deleteIfExists(path(id)); }
    public Path root() { return root; }
    public void removeOrphans(Set<UUID> known) throws IOException {
        try(var files=Files.list(root)) {
            for(Path p:files.toList()) {
                if(p.toString().endsWith(".tmp")) { Files.deleteIfExists(p); continue; }
                if(p.toString().endsWith(".enc")) {
                    try { if(!known.contains(UUID.fromString(p.getFileName().toString().replace(".enc","")))) Files.deleteIfExists(p); }
                    catch(IllegalArgumentException ignored) { /* Do not remove unknown user files. */ }
                }
            }
        }
    }
}
