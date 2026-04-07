package com.glmapper.coding.core.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ExecutionAuditLogger {
    private static final Logger log = LoggerFactory.getLogger(ExecutionAuditLogger.class);

    public void logExecution(ExecutionContext context, String command, ExecutionResult result) {
        log.info("AUDIT: namespace={} sessionId={} userId={} command={} exitCode={} durationMs={} timeout={} truncated={}",
                context.namespace(),
                context.sessionId(),
                context.userId(),
                truncateCommand(command),
                result.exitCode(),
                result.durationMs(),
                result.timeout(),
                result.truncated()
        );
    }

    public void logFileAccess(ExecutionContext context, String operation, String path) {
        log.info("AUDIT: namespace={} sessionId={} userId={} operation={} path={}",
                context.namespace(),
                context.sessionId(),
                context.userId(),
                operation,
                path
        );
    }

    private String truncateCommand(String command) {
        if (command.length() > 200) {
            return command.substring(0, 200) + "...";
        }
        return command;
    }
}
