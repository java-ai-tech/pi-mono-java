package com.glmapper.coding.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "pi.tools.builtin")
public class BuiltinToolsProperties {

    private boolean enabled = true;
    private List<String> available = List.of("read", "bash", "edit", "write", "grep", "find", "ls");
    private List<String> defaultEnabled = List.of("read", "bash", "edit", "write");

    private Read read = new Read();
    private Bash bash = new Bash();
    private Grep grep = new Grep();
    private Find find = new Find();
    private Ls ls = new Ls();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAvailable() {
        return available;
    }

    public void setAvailable(List<String> available) {
        this.available = available;
    }

    public List<String> getDefaultEnabled() {
        return defaultEnabled;
    }

    public void setDefaultEnabled(List<String> defaultEnabled) {
        this.defaultEnabled = defaultEnabled;
    }

    public Read getRead() {
        return read;
    }

    public void setRead(Read read) {
        this.read = read;
    }

    public Bash getBash() {
        return bash;
    }

    public void setBash(Bash bash) {
        this.bash = bash;
    }

    public Grep getGrep() {
        return grep;
    }

    public void setGrep(Grep grep) {
        this.grep = grep;
    }

    public Find getFind() {
        return find;
    }

    public void setFind(Find find) {
        this.find = find;
    }

    public Ls getLs() {
        return ls;
    }

    public void setLs(Ls ls) {
        this.ls = ls;
    }

    public static class Read {
        private int maxLines = 2000;
        private int maxBytes = 50 * 1024;

        public int getMaxLines() {
            return maxLines;
        }

        public void setMaxLines(int maxLines) {
            this.maxLines = maxLines;
        }

        public int getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public static class Bash {
        /** Default timeout for bash tool when request omits timeout. */
        private int defaultTimeoutSeconds = 1800;
        private int maxLines = 2000;
        private int maxBytes = 50 * 1024;

        public int getDefaultTimeoutSeconds() {
            return defaultTimeoutSeconds;
        }

        public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
            this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        }

        public int getMaxLines() {
            return maxLines;
        }

        public void setMaxLines(int maxLines) {
            this.maxLines = maxLines;
        }

        public int getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public static class Grep {
        private int defaultLimit = 100;
        private int maxLineLength = 500;
        private int maxBytes = 50 * 1024;

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getMaxLineLength() {
            return maxLineLength;
        }

        public void setMaxLineLength(int maxLineLength) {
            this.maxLineLength = maxLineLength;
        }

        public int getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public static class Find {
        private int defaultLimit = 1000;
        private int maxBytes = 50 * 1024;

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public static class Ls {
        private int defaultLimit = 500;
        private int maxBytes = 50 * 1024;

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
        }
    }
}
