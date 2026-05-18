package com.glmapper.coding.core.cluster;

import com.glmapper.coding.core.config.PiAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

@Component
public class ClusterNodeIdentity {
    private static final Logger log = LoggerFactory.getLogger(ClusterNodeIdentity.class);

    private final String nodeId;

    public ClusterNodeIdentity(PiAgentProperties properties) {
        this.nodeId = resolveNodeId(properties);
        log.info("Cluster node identity resolved: nodeId={}", this.nodeId);
    }

    public String getNodeId() {
        return nodeId;
    }

    private String resolveNodeId(PiAgentProperties properties) {
        PiAgentProperties.ClusterConfig cluster = properties.cluster();
        if (cluster != null && cluster.nodeId() != null && !cluster.nodeId().isBlank()) {
            return cluster.nodeId();
        }

        String podName = System.getenv("POD_NAME");
        if (podName != null && !podName.isBlank()) {
            return podName;
        }

        String hostname = resolveHostname();
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }

        return UUID.randomUUID().toString();
    }

    private String resolveHostname() {
        String envHostname = System.getenv("HOSTNAME");
        if (envHostname != null && !envHostname.isBlank()) {
            return envHostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.warn("Failed to resolve hostname", e);
            return null;
        }
    }
}
