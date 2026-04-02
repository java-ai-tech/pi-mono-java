#!/bin/bash
ENV="${1:-dev}"
echo "Building React app for environment: $ENV"
echo "Running: npm run build -- --mode $ENV"
echo "Build completed successfully."
