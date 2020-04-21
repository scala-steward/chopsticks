#!/usr/bin/env bash
set -euo pipefail

publish_fdb_jar() {
  VERSION=${1:?"Version is required"}
  SHOPSTIC_BINTRAY_API_KEY=${SHOPSTIC_BINTRAY_API_KEY:?"SHOPSTIC_BINTRAY_API_KEY env variable is required"}

  TEMP=$(mktemp -d)
  trap "rm -Rf ${TEMP}" EXIT

  cd "${TEMP}"

  wget "https://www.foundationdb.org/downloads/6.2.19/bindings/java/fdb-java-${VERSION}.jar"
  wget "https://www.foundationdb.org/downloads/6.2.19/bindings/java/fdb-java-${VERSION}-javadoc.jar"

  mkdir fdb
  wget -O - https://github.com/apple/foundationdb/archive/6.2.19.tar.gz | tar -xz --strip-components=1 -C ./fdb
  jar cf "fdb-java-${VERSION}-sources.jar" -C ./fdb/bindings/java/src/main .

  cat << EOF > "fdb-java-${VERSION}.pom"
<project xmlns="http://maven.apache.org/POM/4.0.0"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                      http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>org.foundationdb</groupId>
  <artifactId>fdb-java</artifactId>
  <version>${VERSION}</version>
  <packaging>jar</packaging>

  <name>foundationdb-java</name>
  <description>Java bindings for the FoundationDB database. These bindings require the FoundationDB client, which is under a different license. The client can be obtained from https://www.foundationdb.org/download/.</description>
  <inceptionYear>2010</inceptionYear>
  <url>https://www.foundationdb.org</url>

  <organization>
    <name>FoundationDB</name>
    <url>https://www.foundationdb.org</url>
  </organization>

  <developers>
    <developer>
        <name>FoundationDB</name>
    </developer>
  </developers>

  <scm>
    <url>http://0.0.0.0</url>
  </scm>

  <licenses>
    <license>
      <name>The Apache v2 License</name>
      <url>http://www.apache.org/licenses/</url>
    </license>
  </licenses>

</project>
EOF

  FILES=("fdb-java-${VERSION}.jar" "fdb-java-${VERSION}-javadoc.jar" "fdb-java-${VERSION}-sources.jar" "fdb-java-${VERSION}.pom")

  for FILE in "${FILES[@]}" ; do
    echo ""
    echo "Uploading ${FILE}..."
    curl \
      -T "${FILE}" \
      "-ushopstic:${SHOPSTIC_BINTRAY_API_KEY}" \
      "https://api.bintray.com/content/shopstic/maven/org.foundationdb:fdb-java/${VERSION}/org/foundationdb/fdb-java/${VERSION}/${FILE}"
  done

  echo "---------------------------"
  echo "Publishing..."
  curl \
    -X POST \
    "-ushopstic:${SHOPSTIC_BINTRAY_API_KEY}" \
    "https://api.bintray.com/content/shopstic/maven/org.foundationdb:fdb-java/${VERSION}/publish"
}

loc() {
  find . -type f \( -name "*.scala" -o -name "*.sbt" -o -name "*.proto" -o -name "*.conf" \) -not -path "./*/target/*" | xargs wc -l | awk '{total += $1} END{print total}'
}

"$@"