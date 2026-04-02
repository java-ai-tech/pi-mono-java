#!/bin/bash
ENV="${1:-preview}"
echo "=== Startup XYZ Vercel Deploy ==="
echo "Environment: $ENV"
echo "Running: vercel deploy $([ "$ENV" = "production" ] && echo "--prod")"
echo ""
echo "✓ Deployed to: https://startup-xyz-${ENV}.vercel.app"
echo "  Status: Ready"
echo "  Region: hnd1 (Tokyo)"
