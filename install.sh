#!/usr/bin/env bash
# install.sh — build sameas-bench-java and install CLI to ~/.local/bin/
#
# Usage:
#   ./install.sh              # installs to ~/.local/bin/sameas-bench
#   PREFIX=/usr/local ./install.sh   # installs to /usr/local/bin/sameas-bench
set -euo pipefail

PREFIX="${PREFIX:-$HOME/.local}"
BIN_DIR="$PREFIX/bin"
LIB_DIR="$PREFIX/lib/sameas-bench"

# Locate Java (17+ required)
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"
JAVA_VER=$("$JAVA" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
if [ "${JAVA_VER:-0}" -lt 17 ] 2>/dev/null; then
    echo "Error: Java 17+ required (found: $("$JAVA" -version 2>&1 | head -1))" >&2
    exit 1
fi

# Locate Maven (mvnw first, then PATH, then sdkman)
MVN=""
if [ -x "./mvnw" ]; then
    MVN="./mvnw"
elif command -v mvn &>/dev/null; then
    MVN="mvn"
elif [ -x "$HOME/.sdkman/candidates/maven/current/bin/mvn" ]; then
    MVN="$HOME/.sdkman/candidates/maven/current/bin/mvn"
else
    # try any sdkman maven version
    MVN=$(ls "$HOME/.sdkman/candidates/maven"/*/bin/mvn 2>/dev/null | head -1 || true)
fi
if [ -z "$MVN" ]; then
    echo "Error: Maven not found. Install via: sdk install maven" >&2
    exit 1
fi

echo "Building fat JAR …"
"$MVN" package -q -DskipTests

JAR="target/sameas-bench.jar"
if [ ! -f "$JAR" ]; then
    echo "Error: build failed — $JAR not found" >&2
    exit 1
fi

echo "Installing to $LIB_DIR …"
mkdir -p "$LIB_DIR" "$BIN_DIR"
cp "$JAR" "$LIB_DIR/sameas-bench.jar"

WRAPPER="$BIN_DIR/sameas-bench-java"
cat > "$WRAPPER" <<EOF
#!/usr/bin/env bash
exec "\${JAVA_HOME:+\$JAVA_HOME/bin/}java" \${JAVA_OPTS:--Xmx16g} -jar "$LIB_DIR/sameas-bench.jar" "\$@"
EOF
chmod +x "$WRAPPER"

echo "Installed: $WRAPPER"
if ! echo "$PATH" | grep -q "$BIN_DIR"; then
    echo ""
    echo "  Add to PATH:  export PATH=\"$BIN_DIR:\$PATH\""
fi
echo ""
echo "Quick test:  sameas-bench-java smoke"
