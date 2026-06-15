FROM eclipse-temurin:17-jdk

ENV OPENROUTER_API_KEY=""
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV GRADLE_VERSION=8.5
ENV GRADLE_HOME=/opt/gradle
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$GRADLE_HOME/bin

ARG API_KEY
ENV OPENROUTER_API_KEY=${API_KEY}

RUN apt-get update && apt-get install -y --no-install-recommends \
        wget unzip git curl && rm -rf /var/lib/apt/lists/*

RUN mkdir -p $ANDROID_HOME/cmdline-tools && \
    cd $ANDROID_HOME/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip && \
    unzip -q cmdline-tools.zip && \
    mv cmdline-tools latest && \
    rm cmdline-tools.zip && \
    yes | sdkmanager --licenses > /dev/null && \
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" > /dev/null

RUN wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -O /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    mv /opt/gradle-${GRADLE_VERSION} ${GRADLE_HOME} && \
    rm /tmp/gradle.zip && \
    gradle --version

WORKDIR /project
COPY . .

# Skip `gradle wrapper` validation (it does a HEAD probe that flakes in CI);
# run gradle directly — it's already on PATH.
# Pass API_KEY both as -P gradle property (the only way build.gradle.kts sees it
# via project.findProperty) and as env var (defensive, in case any plugin reads it).
ARG API_KEY
RUN API_KEY="$API_KEY" gradle assembleDebug --no-daemon -x test \
    -POPENROUTER_API_KEY="$API_KEY"

CMD ["echo", "Build complete. APK at app/build/outputs/apk/debug/app-debug.apk"]
