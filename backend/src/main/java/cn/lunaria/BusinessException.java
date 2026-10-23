package cn.lunaria;

public class BusinessException extends RuntimeException {
    public final int status;
    public BusinessException(int status, String message) { super(message); this.status=status; }
    static void require(boolean condition, String message) { if (!condition) throw new BusinessException(409,message); }
}
