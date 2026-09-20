#!/usr/bin/env bash
# WorkBuddy Maven wrapper —— 绕过 MSYS 下 mvn 脚本 classpath 路径转换问题，
# 直接通过 Java 调用 plexus classworlds Launcher 执行 Maven。
# 用法：./mvnw-wb.sh <maven 参数...>（如 ./mvnw-wb.sh compile -Dmaven.test.skip=true）
MW="D:\\apache-maven-3.9.10\\boot\\plexus-classworlds-2.9.0.jar"
PWD_WIN=$(cygpath -w "$(pwd)" 2>/dev/null || echo "$(pwd)")
exec java -classpath "$MW" \
  "-Dclassworlds.conf=D:\\apache-maven-3.9.10\\bin\\m2.conf" \
  "-Dmaven.home=D:\\apache-maven-3.9.10" \
  "-Dmaven.multiModuleProjectDirectory=$PWD_WIN" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
