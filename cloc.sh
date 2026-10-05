#!/bin/bash
# Count lines of code in each component of the project
# I can't think of any immedate utility of this beyond curiosity
for kind in main test; do
  printf '%-5s ' "$kind"
  find engine -path "*/src/$kind/*" \( -name '*.scala' -o -name '*.java' \) -type f -print0 \
    | xargs -0 cat | wc -l
done
printf 'domains ' ; find domains \( -name build -o -name .gradle \) -prune -o \
  \( -name '*.java' \) -type f -print0 | xargs -0 cat | wc -l
