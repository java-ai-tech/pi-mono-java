---
entrypoint: "./deploy.sh"
args_schema: '{"type":"object","properties":{"service":{"type":"string"},"env":{"type":"string","enum":["staging","prod"]}}}'
---
# Deploy K8s
Deploy a service to Acme Corp's Kubernetes cluster.
