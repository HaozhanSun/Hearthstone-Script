# Build

## Environment

- Windows 11 x64
- Microsoft OpenJDK 25.0.4 (user-local ZIP installation)
- Maven Wrapper 3.3.2, resolving Apache Maven 3.8.8

## Baseline command

```powershell
$jdk = 'C:\Users\Haozhan Sun\AppData\Local\HaozhanBuild\jdk25\jdk-25.0.4+7'
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
& '.\mvnw.cmd' -DskipTests package
```

Result: `BUILD SUCCESS`.

The build produced `hs-script-app/target/hs-script_v4.16.3-GA.zip` and the JVM jar. The first attempt failed before Maven started because Java was absent and the wrapper emitted an unquoted command path from a profile containing a space; `mvnw.cmd` now quotes that command path.
