#!/bin/bash
# Migration script: Reorganize skills directory structure
# Creates public and namespaces directories, moves existing skills to public

set -e

SKILLS_DIR="${1:-./skills}"

echo "Starting skills directory migration..."
echo "Skills directory: $SKILLS_DIR"

# Create new directory structure
mkdir -p "$SKILLS_DIR/public"
mkdir -p "$SKILLS_DIR/namespaces"

# Move existing skills to public (excluding public and namespaces dirs)
for item in "$SKILLS_DIR"/*; do
    basename=$(basename "$item")
    if [ "$basename" != "public" ] && [ "$basename" != "namespaces" ]; then
        echo "Moving $basename to public/"
        mv "$item" "$SKILLS_DIR/public/"
    fi
done

echo "Migration completed successfully"
echo "Structure:"
echo "  $SKILLS_DIR/public/       - Public skills (visible to all namespaces)"
echo "  $SKILLS_DIR/namespaces/   - Namespace-specific skills"
