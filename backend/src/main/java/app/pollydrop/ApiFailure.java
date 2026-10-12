package app.pollydrop;

public class ApiFailure extends RuntimeException {
    final int status;
    final String code;
    public ApiFailure(int status, String code, String message) { super(message); this.status=status; this.code=code; }
}
