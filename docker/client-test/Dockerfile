# Headless Linux environment for the MineQTT Fabric client game tests (:fabric:26.2:runClientGameTest).
# Xvfb provides the X display, Mesa llvmpipe the OpenGL implementation (software rendering, no GPU needed).
FROM eclipse-temurin:25-jdk

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        xvfb xauth mesa-utils \
        libgl1 libgl1-mesa-dri libegl1 libglx-mesa0 libgles2 \
        libx11-6 libxext6 libxrender1 libxtst6 libxi6 libxrandr2 libxcursor1 libxinerama1 libxxf86vm1 \
        fontconfig fonts-dejavu-core adwaita-icon-theme \
        git ca-certificates procps \
 && rm -rf /var/lib/apt/lists/*

# Software OpenGL; OpenAL (bundled with LWJGL) gets a null output device instead of a missing ALSA/Pulse; a cursor
# theme so GLFW finds its standard cursor shapes (otherwise "65547: X11: Standard cursor shape unavailable").
ENV LIBGL_ALWAYS_SOFTWARE=1 \
    GALLIUM_DRIVER=llvmpipe \
    ALSOFT_DRIVERS=null \
    XCURSOR_THEME=Adwaita

COPY in-container.sh /usr/local/bin/mq
# The build context may come from a Windows checkout with CRLF line endings.
RUN sed -i 's/\r$//' /usr/local/bin/mq && chmod +x /usr/local/bin/mq

WORKDIR /work
CMD ["mq", "watchdog"]
