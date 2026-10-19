package com.repomind.lite.errors;

public class BusinessException extends RuntimeException {
    private final FailureType type;
    private final String code;

    public BusinessException(FailureType type, String code, String message) {
        super(message);
        this.type = type;
        this.code = code;
    }

    public FailureType type() { return type; }
    public String code() { return code; }

    public static BusinessException badRequest(String message) {
        return new BusinessException(FailureType.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    public static BusinessException notFound(String code, String message) {
        return new BusinessException(FailureType.NOT_FOUND, code, message);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(FailureType.CONFLICT, code, message);
    }

    public static BusinessException unavailable(String code, String message) {
        return new BusinessException(FailureType.SERVICE_UNAVAILABLE, code, message);
    }

    public static BusinessException rateLimited(String message) {
        return new BusinessException(FailureType.RATE_LIMITED, "RATE_LIMITED", message);
    }
}
