# Skarmetoo Desktop

Standalone Windows desktop client for processing mobile images with LM Studio.

## Requirements

- Windows
- JDK 21. Android Studio's bundled JDK is usually at:
  `C:\Program Files\Android\Android Studio\jbr`
- LM Studio with a loaded vision-capable model

If Java is not on `PATH`, run from PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## Start LM Studio

1. Open LM Studio and load a vision-capable model.
2. Start the local server with the OpenAI-compatible API enabled.
3. Use the default server address:
   `http://127.0.0.1:1234/v1`

## Run the desktop client

Open a second terminal in this directory:

```bat
gradlew.bat run
```

You can also build the standalone distribution:

```bat
gradlew.bat build
gradlew.bat installDist
```

The runnable files are generated under `build\install\skarmetoo-desktop\bin`.

## Connect to mobile

1. Start **Desktop connection** in the Skarmetoo Android Settings screen.
2. Copy the displayed phone IP address.
3. Enter that IP in the desktop client and select **Connect**.
4. Confirm the LM Studio endpoint and click **Refresh models**.
5. Select the loaded vision model and click **Process registered images**.

The desktop project is independent from the Android Gradle project. It has its own `settings.gradle.kts`, `build.gradle.kts`, and Gradle wrapper.
