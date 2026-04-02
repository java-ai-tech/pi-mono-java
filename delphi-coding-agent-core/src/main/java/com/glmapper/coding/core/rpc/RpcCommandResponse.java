package com.glmapper.coding.core.rpc;

public record RpcCommandResponse(
        String id,
        String type,
        String command,
        boolean success,
        Object data,
        String error
) {
    public static RpcCommandResponse ok(String id, String command, Object data) {
        return new RpcCommandResponse(id, "response", command, true, data, null);
    }

    public static RpcCommandResponse ok(String id, String command) {
        return new RpcCommandResponse(id, "response", command, true, null, null);
    }

    public static RpcCommandResponse error(String id, String command, String error) {
        return new RpcCommandResponse(id, "response", command, false, null, error);
    }
}
