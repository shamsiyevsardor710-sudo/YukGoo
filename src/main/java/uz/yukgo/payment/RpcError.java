package uz.yukgo.payment;

/** Payme yoki Paynet so'roviga xato javobi (JSON-RPC "error" qismi). */
class RpcError extends RuntimeException {
    final int code;
    /** Payme: xato qaysi "account" maydoniga tegishli (masalan, "order_id") */
    final String data;

    RpcError(int code) {
        this(code, null, null);
    }

    RpcError(int code, String data) {
        this(code, data, null);
    }

    RpcError(int code, String data, String detail) {
        super(detail != null ? detail : "code " + code, null, false, false);
        this.code = code;
        this.data = data;
    }
}
