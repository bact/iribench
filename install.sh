#!/usr/bin/env bash
# install.sh — build iribench and install CLI to ~/.local/bin/
#
# Usage:
#   ./install.sh              # installs to ~/.local/bin/iribench
#   PREFIX=/usr/local ./install.sh   # installs to /usr/local/bin/iribench
set -euo pipefail

PREFIX="${PREFIX:-$HOME/.local}"
BIN_DIR="$PREFIX/bin"
LIB_DIR="$PREFIX/lib/iribench"

# Locate Java (25+ required)
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"
JAVA_VER=$("$JAVA" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
if [ "${JAVA_VER:-0}" -lt 25 ] 2>/dev/null; then
    echo "Error: Java 25+ required (found: $("$JAVA" -version 2>&1 | head -1))" >&2
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

JAR="target/iribench.jar"
if [ ! -f "$JAR" ]; then
    echo "Error: build failed — $JAR not found" >&2
    exit 1
fi

echo "Installing to $LIB_DIR …"
mkdir -p "$LIB_DIR" "$BIN_DIR"
cp "$JAR" "$LIB_DIR/iribench.jar"

WRAPPER="$BIN_DIR/iribench"
cat > "$WRAPPER" <<EOF
#!/usr/bin/env bash
exec "\${JAVA_HOME:+\$JAVA_HOME/bin/}java" \${JAVA_OPTS:--Xms2g -Xmx16g} -jar "$LIB_DIR/iribench.jar" "\$@"
EOF
chmod +x "$WRAPPER"

echo "Installed: $WRAPPER"
if ! echo "$PATH" | grep -q "$BIN_DIR"; then
    echo ""
    echo "  Add to PATH:  export PATH=\"$BIN_DIR:\$PATH\""
fi
echo ""
echo "Smoke test (toy ontology):"
echo "    iribench smoke"
echo ""
echo "Quick test (SPDX ontology, small SBOM data, skip reasoning):"
echo "    iribench quick"
echo ""
echo "Full tests (SPDX ontology):"
echo "    iribench run"
