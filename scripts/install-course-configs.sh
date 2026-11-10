#!/bin/bash
set -e

SRC="/workspace/course/configs"
STUDENT="/workspace/student-work"
DST="/workspace/chipyard/generators/chipyard/src/main/scala/config"

if [ ! -d "$SRC" ]; then
    echo "Course configuration directory not found: $SRC"
    exit 1
fi

echo "Linking course Chipyard configurations..."

for file in "$SRC"/*.scala; do
    if [ -e "$file" ]; then
        name=$(basename "$file")

        ln -sf "$file" "$DST/$name"

        echo "  $name"
    fi
done

echo "Course configurations ready."

if [ -d "$STUDENT" ]; then
    echo "Linking student Chipyard configurations..."

    while IFS= read -r -d '' file; do
        name=$(basename "$file")

        if [ -e "$DST/$name" ] || [ -L "$DST/$name" ]; then
            if [ "$(readlink "$DST/$name")" != "$file" ]; then
                echo "  SKIPPED ${file#$STUDENT/}: $name already exists in Chipyard" >&2
                continue
            fi
        fi

        ln -sf "$file" "$DST/$name"

        echo "  ${file#$STUDENT/}"
    done < <(find "$STUDENT" -path "$STUDENT/patches" -prune -o -type f -name '*.scala' -print0)

    echo "Student configurations ready."
fi
